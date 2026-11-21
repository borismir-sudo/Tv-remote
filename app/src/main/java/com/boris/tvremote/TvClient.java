package com.boris.tvremote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** LG webOS "second screen" (SSAP) protocol client. No Android dependencies. */
public class TvClient {

    public interface Listener {
        void onStatus(String text, boolean connected);
        void onClientKey(String key);
        void onMac(String mac);
        void onError(String text);
        void onLog(String text);
    }

    static final int CONNECT_TIMEOUT_MS = 4000;
    static final int PAIR_TIMEOUT_MS = 60000;
    static final int RESPONSE_TIMEOUT_MS = 5000;

    // Defaults; tests point these at a local mock server.
    int wssPort = 3001;
    int wsPort = 3000;

    private final Listener listener;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();      // buttons & commands
    private final ExecutorService ptr = Executors.newSingleThreadExecutor();       // mouse moves — never waits for anything slow
    private final ExecutorService slow = Executors.newSingleThreadExecutor();      // screenshots & status polling
    private final Object connectLock = new Object();
    private final Object inputLock = new Object();
    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<String, BlockingQueue<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final BlockingQueue<Map<String, Object>> regQueue = new ArrayBlockingQueue<>(8);

    private volatile String ip;
    private volatile String clientKey;
    private volatile WsConn main;
    private volatile WsConn input;
    private volatile boolean useTls = true;

    public TvClient(Listener listener) { this.listener = listener; }

    public void setTarget(String ip, String clientKey) {
        // Set the address right away so the mouse / screenshot threads never see an empty IP.
        final boolean changed = this.ip == null || !this.ip.equals(ip);
        this.ip = ip;
        this.clientKey = (clientKey == null || clientKey.isEmpty()) ? null : clientKey;
        if (changed) exec.execute(this::dropConnections);
    }

    public String getIp() { return ip; }

    public boolean isConnected() { WsConn m = main; return m != null && m.isOpen(); }

    /** Connect now (in background) so the first button press is instant. */
    public void connect() { exec.execute(() -> { try { ensureMain(); } catch (Exception e) { fail(e); } }); }

    public void shutdown() {
        exec.execute(this::dropConnections);
        exec.shutdown();
        ptr.shutdownNow();
        slow.shutdownNow();
    }

    // ---------------- commands ----------------

    public void button(String name) {
        exec.execute(() -> {
            try {
                sendInput("type:button\nname:" + name + "\n\n");
            } catch (Exception e) {
                // one retry with a fresh input socket
                try { closeInput(); sendInput("type:button\nname:" + name + "\n\n"); }
                catch (Exception e2) { fail(e2); }
            }
        });
    }

    public void ssap(String uri) { ssap(uri, null); }

    public void ssap(String uri, Map<String, Object> payload) {
        exec.execute(() -> {
            try { request(uri, payload); } catch (Exception e) { fail(e); }
        });
    }

    /** Press a key like the real remote; if the key socket fails, try the SSAP command. */
    private void key(String button, String fallbackUri) {
        exec.execute(() -> {
            try {
                sendInput("type:button\nname:" + button + "\n\n");
                listener.onLog(L.s("кнопка ", "key ") + button);
            } catch (Exception e) {
                listener.onLog(L.s("кнопка ", "key ") + button + L.s(" не прошла: ", " failed: ") + e.getMessage());
                if (fallbackUri == null) { fail(e); return; }
                try { request(fallbackUri, null); } catch (Exception e2) { fail(e2); }
            }
        });
    }

    public void volumeUp() { key("VOLUMEUP", "audio/volumeUp"); }
    public void volumeDown() { key("VOLUMEDOWN", "audio/volumeDown"); }
    public void channelUp() { key("CHANNELUP", "tv/channelUp"); }
    public void channelDown() { key("CHANNELDOWN", "tv/channelDown"); }
    public void toggleMute() { key("MUTE", null); }
    public void powerOff() { ssap("system/turnOff"); }
    public void launch(String appId) { ssap("system.launcher/launch", Json.map("id", appId)); }

    /** Open YouTube on the TV with a search query. */
    public void youtubeSearch(String query) {
        String q;
        try { q = java.net.URLEncoder.encode(query, "UTF-8"); } catch (Exception e) { q = query; }
        ssap("system.launcher/launch", Json.map("id", "youtube.leanback.v4",
                "contentId", "https://www.youtube.com/tv?launch=voice&vq=" + q));
    }

    /** Type text into the TV's active text field and optionally press Enter. */
    public void typeText(String text, boolean enter) {
        exec.execute(() -> {
            try {
                request("com.webos.service.ime/insertText", Json.map("text", text, "replace", Boolean.FALSE));
                if (enter) request("com.webos.service.ime/sendEnterKey", null);
            } catch (Exception e) {
                listener.onLog(L.s("ввод текста не прошёл (", "text input failed (") + e.getMessage() + L.s(") → ищу в YouTube", ") → searching YouTube"));
                youtubeSearch(text);
            }
        });
    }

    public void deleteChars(int count) {
        ssap("com.webos.service.ime/deleteCharacters", Json.map("count", count));
    }

    // ---------------- connection ----------------

    private void ensureMain() throws Exception {
        WsConn m = main;
        if (m != null && m.isOpen()) return;
        synchronized (connectLock) {
            m = main;
            if (m != null && m.isOpen()) return;
            connectNow();
        }
    }

    private void connectNow() throws Exception {
        if (ip == null || ip.isEmpty()) throw new IOException(L.s("Не задан IP телевизора (⚙)", "TV IP not set (⚙)"));
        listener.onStatus(L.s("Подключаюсь к ", "Connecting to ") + ip + "…", false);
        dropConnections();
        regQueue.clear();

        WsConn c = openMain();
        main = c;
        register(c);
        listener.onStatus(L.s("Подключено: ", "Connected: ") + ip, true);
        // Learn the MAC for Wake-on-LAN (best effort).
        try {
            Map<String, Object> info = request("com.webos.service.connectionmanager/getinfo", null);
            String mac = findMac(info);
            if (mac != null) listener.onMac(mac);
        } catch (Exception ignored) { }
    }

    private WsConn openMain() throws IOException {
        String wss = "wss://" + ip + ":" + wssPort;
        String ws = "ws://" + ip + ":" + wsPort;
        String first = useTls ? wss : ws, second = useTls ? ws : wss;
        try {
            return WsConn.open(first, CONNECT_TIMEOUT_MS, mainHandler);
        } catch (IOException e) {
            WsConn c = WsConn.open(second, CONNECT_TIMEOUT_MS, mainHandler);
            useTls = second.startsWith("wss");
            return c;
        }
    }

    private final WsConn.Handler mainHandler = new WsConn.Handler() {
        @Override public void onText(WsConn c, String text) {
            Map<String, Object> msg;
            try { msg = Json.obj(Json.parse(text)); } catch (RuntimeException e) { return; }
            if (msg == null) return;
            String id = String.valueOf(msg.get("id"));
            if (id.startsWith("register")) { regQueue.offer(msg); return; }
            BlockingQueue<Map<String, Object>> q = pending.get(id);
            if (q != null) q.offer(msg);
        }

        @Override public void onClosed(WsConn c, Exception reason) {
            if (c == main) {
                main = null;
                closeInput();
                listener.onStatus(L.s("Нет связи с телевизором", "Lost connection to TV"), false);
            }
        }
    };

    private void register(WsConn c) throws Exception {
        boolean signed = true;
        c.sendText(Json.stringify(registrationMsg(signed)));
        Map<String, Object> r = regQueue.poll(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (r == null) throw new IOException(L.s("Телевизор не ответил на регистрацию", "TV did not answer registration"));
        if ("error".equals(r.get("type"))) {
            // Newer firmware rejects the signed manifest: retry unsigned.
            signed = false;
            c.sendText(Json.stringify(registrationMsg(signed)));
            r = regQueue.poll(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (r == null) throw new IOException(L.s("Телевизор не ответил на регистрацию", "TV did not answer registration"));
        }
        Map<String, Object> p = Json.obj(r.get("payload"));
        if ("response".equals(r.get("type")) && p != null && "PROMPT".equals(p.get("pairingType"))) {
            listener.onStatus(L.s("Нажмите «Принять» на экране телевизора (пультом)", "Press “Accept” on the TV screen (with the remote)"), false);
            r = regQueue.poll(PAIR_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (r == null) throw new IOException(L.s("Не подтвердили на телевизоре за 60 секунд", "Not accepted on the TV within 60 seconds"));
            p = Json.obj(r.get("payload"));
        }
        if ("error".equals(r.get("type"))) throw new IOException(L.s("Телевизор отказал: ", "TV refused: ") + r.get("error"));
        if (!"registered".equals(r.get("type"))) throw new IOException(L.s("Неожиданный ответ: ", "Unexpected reply: ") + r.get("type"));
        String key = p == null ? null : Json.str(p.get("client-key"));
        if (key != null && !key.isEmpty() && !key.equals(clientKey)) {
            clientKey = key;
            listener.onClientKey(key);
        }
    }

    private Map<String, Object> request(String uri, Map<String, Object> payload) throws Exception {
        return request(uri, payload, false);
    }

    private Map<String, Object> request(String uri, Map<String, Object> payload, boolean quiet) throws Exception {
        ensureMain();
        String id = "req_" + seq.getAndIncrement();
        BlockingQueue<Map<String, Object>> q = new ArrayBlockingQueue<>(2);
        pending.put(id, q);
        try {
            Map<String, Object> msg = Json.map("id", id, "type", "request", "uri", "ssap://" + uri);
            if (payload != null) msg.put("payload", payload);
            main.sendText(Json.stringify(msg));
            Map<String, Object> r = q.poll(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (r == null) { if (!quiet) listener.onLog(uri + L.s(" → нет ответа", " → no reply")); throw new IOException(L.s("Телевизор не ответил", "TV did not answer")); }
            if (!quiet) listener.onLog(uri + " → " + abbreviate(Json.stringify(r.get("type")) + " " + Json.stringify(r.containsKey("error") ? r.get("error") : r.get("payload"))));
            if ("error".equals(r.get("type"))) throw new IOException(String.valueOf(r.get("error")));
            Map<String, Object> p = Json.obj(r.get("payload"));
            if (p != null && Boolean.FALSE.equals(p.get("returnValue"))) {
                throw new IOException(String.valueOf(p.containsKey("errorText") ? p.get("errorText") : L.s("ошибка команды", "command error")));
            }
            return p;
        } finally {
            pending.remove(id);
        }
    }

    private WsConn ensureInput() throws Exception {
        WsConn in = input;
        if (in != null && in.isOpen()) return in;
        synchronized (inputLock) {
          in = input;
          if (in == null || !in.isOpen()) {
            Map<String, Object> r = request("com.webos.service.networkinput/getPointerInputSocket", null);
            String path = r == null ? null : Json.str(r.get("socketPath"));
            if (path == null) throw new IOException(L.s("Нет сокета кнопок", "No key socket"));
            in = openInput(path);
            input = in;
          }
          return in;
        }
    }

    private void sendInput(String msg) throws Exception {
        ensureInput().sendText(msg);
    }

    /** Open the pointer/key socket ahead of time so the touchpad reacts instantly. */
    public void prepareInput() {
        ptr.execute(() -> { try { ensureInput(); } catch (Exception e) { listener.onLog(L.s("сокет мыши: ", "mouse socket: ") + e.getMessage()); } });
    }

    private volatile long lastPointerError;

    private void pointer(String msg) { ptr.execute(() -> pointerNow(msg)); }

    private void pointerNow(String msg) {
        try {
            sendInput(msg);
        } catch (Exception e) {
            closeInput();
            long now = System.currentTimeMillis();
            if (now - lastPointerError > 3000) { lastPointerError = now; fail(e); }
        }
    }

    public interface InfoCallback { void onInfo(String appId, Integer volume, Boolean muted); }
    public interface ShotCallback { void onShot(byte[] jpeg); }

    /** What is on the TV now (foreground app, volume). Only while connected; never triggers a reconnect. */
    public void requestInfo(InfoCallback cb) {
        slow.execute(() -> {
            if (!isConnected()) return;
            String app = null; Integer vol = null; Boolean mute = null;
            try {
                Map<String, Object> a = request("com.webos.applicationManager/getForegroundAppInfo", null, true);
                if (a != null) app = Json.str(a.get("appId"));
            } catch (Exception ignored) { }
            try {
                Map<String, Object> v = request("audio/getVolume", null, true);
                Map<String, Object> st = v == null ? null : Json.obj(v.get("volumeStatus"));
                Map<String, Object> src = st != null ? st : v;
                if (src != null) {
                    Object n = src.get("volume");
                    if (n instanceof Number) vol = ((Number) n).intValue();
                    Object m = src.containsKey("muteStatus") ? src.get("muteStatus") : src.get("muted");
                    if (m instanceof Boolean) mute = (Boolean) m;
                }
            } catch (Exception ignored) { }
            cb.onInfo(app, vol, mute);
        });
    }

    private volatile boolean shotUnsupported;
    private int shotFailures;
    private int shotVariant = -1;          // which request form worked last
    private final java.util.Set<String> shotLogged = new java.util.HashSet<>();

    /** Request forms seen on different webOS versions. */
    @SuppressWarnings("unchecked")
    private static final Object[][] SHOT_VARIANTS = {
            {"tv/executeOneShot", Json.map("path", "/tmp/boris_remote.jpg", "method", "DISPLAY", "format", "JPG")},
            {"tv/executeOneShot", Json.map("path", "/tmp/boris_remote.jpg", "method", "SCREEN", "format", "JPG", "width", 960, "height", 540)},
            {"tv/executeOneShot", null},
            {"com.webos.service.tv.capture/executeOneShot", Json.map("path", "/tmp/boris_remote.jpg", "method", "DISPLAY", "format", "JPG")},
            {"com.webos.service.capture/executeOneShot", Json.map("path", "/tmp/boris_remote.jpg", "method", "DISPLAY", "format", "JPG")},
    };

    private void logOnce(String key, String msg) {
        if (shotLogged.add(key)) listener.onLog(msg);
    }

    /** Grab a still of the TV picture. Tries every known request form and both ports. */
    @SuppressWarnings("unchecked")
    public void requestScreenshot(ShotCallback cb) {
        slow.execute(() -> {
            if (!isConnected() || shotUnsupported) { cb.onShot(null); return; }
            int start = shotVariant >= 0 ? shotVariant : 0;
            for (int k = 0; k < SHOT_VARIANTS.length; k++) {
                int v = (start + k) % SHOT_VARIANTS.length;
                if (shotVariant >= 0 && v != shotVariant) break;   // known-good form: don't retry others every time
                String uri = (String) SHOT_VARIANTS[v][0];
                Map<String, Object> payload = (Map<String, Object>) SHOT_VARIANTS[v][1];
                String imageUri;
                try {
                    Map<String, Object> r = request(uri, payload, true);
                    imageUri = r == null ? null : Json.str(r.get("imageUri"));
                    if (imageUri == null) { logOnce("noimg" + v, "📷 " + uri + " → " + L.s("нет ссылки на картинку", "no image link")); continue; }
                } catch (Exception e) {
                    logOnce("err" + v, "📷 " + uri + " → " + e.getMessage());
                    continue;
                }
                byte[] img = fetchImage(imageUri);
                if (img != null) {
                    if (shotVariant != v) listener.onLog("📷 " + L.s("снимок экрана работает", "screenshot works") + " (" + uri + ")");
                    shotVariant = v;
                    shotFailures = 0;
                    cb.onShot(img);
                    return;
                }
            }
            if (++shotFailures >= 3) {
                shotUnsupported = true;
                listener.onLog("📷 " + L.s("снимок экрана ТВ недоступен — телевизор отказывает (подробности выше)",
                        "TV screenshot unavailable — the TV refuses (details above)"));
            }
            cb.onShot(null);
        });
    }

    /** Download the capture; newer firmware closes plain port 3000, so also try the TLS port 3001. */
    private byte[] fetchImage(String imageUri) {
        List<String> urls = new ArrayList<>();
        String u = imageUri.startsWith("http") ? imageUri : "http://" + ip + ":" + wsPort + (imageUri.startsWith("/") ? "" : "/") + imageUri;
        urls.add(u);
        int slash = u.indexOf('/', u.indexOf("//") + 2);
        String rest = slash > 0 ? u.substring(slash) : "/";
        urls.add("https://" + ip + ":" + wssPort + rest);
        urls.add("http://" + ip + ":" + wsPort + rest);
        for (String url : urls) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
                if (c instanceof javax.net.ssl.HttpsURLConnection) {
                    javax.net.ssl.HttpsURLConnection h = (javax.net.ssl.HttpsURLConnection) c;
                    h.setSSLSocketFactory(WsConn.tvTls().getSocketFactory());
                    h.setHostnameVerifier((host, session) -> host.equals(ip));   // only the TV's own address
                }
                c.setConnectTimeout(2500);
                c.setReadTimeout(3000);
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    if (bo.size() > 0) return bo.toByteArray();
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                logOnce("dl" + url, "📷 " + L.s("не скачать ", "can't download ") + url + " → " + e.getMessage());
            }
        }
        return null;
    }

    /** Allow another attempt after the user changes what's on screen. */
    public void resetScreenshot() { slow.execute(() -> { shotUnsupported = false; shotFailures = 0; }); }

    static final int MAX_STEP = 40;

    /** The TV clips large single steps, so send big moves as several small ones. */
    private void sendChunked(int x, int y) {
        while (x != 0 || y != 0) {
            int sx = Math.max(-MAX_STEP, Math.min(MAX_STEP, x));
            int sy = Math.max(-MAX_STEP, Math.min(MAX_STEP, y));
            pointerNow("type:move\ndx:" + sx + "\ndy:" + sy + "\ndown:0\n\n");
            x -= sx;
            y -= sy;
        }
    }

    static final int PACED_STEP = 8;       // small, equal steps …
    static final int PACED_PAUSE_MS = 3;   // … at an even pace, so the TV adds no acceleration

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String moveMsg(int dx, int dy) { return "type:move\ndx:" + dx + "\ndy:" + dy + "\ndown:0\n\n"; }

    /** Push the pointer into the top-left corner: it stops at the edge, so its position is then known exactly. */
    private void parkNow() {
        pointerNow(moveMsg(-1, -1));          // wakes a hidden pointer
        sleepQuiet(60);
        for (int k = 0; k < 220; k++) pointerNow(moveMsg(-60, -60));
        sleepQuiet(80);
    }

    /**
     * Glide along one axis with identical steps at an even pace. Mixing step sizes (diagonals) lets the TV's
     * own acceleration distort the path; one axis at a time keeps the distance exactly proportional.
     */
    private void axisPacedNow(int d, boolean horizontal) {
        int sign = d >= 0 ? 1 : -1, left = Math.abs(d);
        while (left > 0) {
            int st = Math.min(PACED_STEP, left);
            pointerNow(horizontal ? moveMsg(sign * st, 0) : moveMsg(0, sign * st));
            left -= st;
            sleepQuiet(PACED_PAUSE_MS);
        }
    }

    private void movePacedNow(int dx, int dy) {
        axisPacedNow(dx, true);
        axisPacedNow(dy, false);
    }

    /** Absolute pointing: park in the corner, then glide (ux, uy) units from it; optionally click there. */
    public java.util.concurrent.Future<?> pointTo(int ux, int uy, boolean click) {
        return ptr.submit(() -> {
            synchronized (moveLock) { moveDx = 0; moveDy = 0; }
            parkNow();
            movePacedNow(Math.max(0, ux), Math.max(0, uy));
            if (click) {
                sleepQuiet(120);
                pointerNow("type:click\n\n");
            }
        });
    }

    /** Relative glide with even steps (used to correct the pointer after measuring it). */
    public java.util.concurrent.Future<?> nudge(int dx, int dy) {
        return ptr.submit(() -> movePacedNow(dx, dy));
    }

    private final Object moveLock = new Object();
    private int moveDx, moveDy;
    private boolean moveQueued;

    /** Move the TV's on-screen pointer. Moves that pile up while the network is busy are merged into one. */
    public void move(int dx, int dy) {
        synchronized (moveLock) {
            moveDx += dx;
            moveDy += dy;
            if (moveQueued) return;
            moveQueued = true;
        }
        ptr.execute(() -> {
            int x, y;
            synchronized (moveLock) { x = moveDx; y = moveDy; moveDx = 0; moveDy = 0; moveQueued = false; }
            sendChunked(x, y);
        });
    }

    public void click() { pointer("type:click\n\n"); listener.onLog(L.s("клик мышью", "mouse click")); }
    public void scroll(int dx, int dy) { pointer("type:scroll\ndx:" + dx + "\ndy:" + dy + "\n\n"); }

    private WsConn openInput(String path) throws IOException {
        WsConn.Handler h = new WsConn.Handler() {
            @Override public void onText(WsConn c, String text) { }
            @Override public void onClosed(WsConn c, Exception reason) { if (c == input) input = null; }
        };
        List<String> candidates = new ArrayList<>();
        candidates.add(path);
        // Some firmware hands out a ws://...:3000 path even over wss; try the TLS port too.
        int slash = path.indexOf('/', path.indexOf("//") + 2);
        String rest = slash > 0 ? path.substring(slash) : "/";
        String alt = (path.startsWith("wss") ? "ws://" + ip + ":" + wsPort : "wss://" + ip + ":" + wssPort) + rest;
        candidates.add(alt);
        IOException last = null;
        for (String u : candidates) {
            try { return WsConn.open(u, CONNECT_TIMEOUT_MS, h); } catch (IOException e) { last = e; }
        }
        throw last;
    }

    private void closeInput() {
        WsConn in = input;
        input = null;
        if (in != null) in.close();
    }

    private void dropConnections() {
        closeInput();
        WsConn m = main;
        main = null;
        if (m != null) m.close();
    }

    private void fail(Exception e) {
        String m = e.getMessage();
        listener.onError(m == null ? e.getClass().getSimpleName() : m);
    }

    // ---------------- helpers ----------------

    static String abbreviate(String t) { return t.length() > 160 ? t.substring(0, 160) + "…" : t; }

    @SuppressWarnings("unchecked")
    static String findMac(Object o) {
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            // Prefer Wi-Fi (the TV is on Wi-Fi), then wired.
            for (String k : new String[]{"wifiInfo", "wiredInfo"}) {
                Map<String, Object> sub = Json.obj(m.get(k));
                if (sub != null) {
                    String mac = Json.str(sub.get("macAddress"));
                    if (mac != null && mac.matches("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")) return mac.toUpperCase();
                }
            }
            for (Object v : m.values()) { String r = findMac(v); if (r != null) return r; }
        }
        return null;
    }

    static final String SIGNATURE =
            "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbm"
            + "ctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR"
            + "+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRy"
            + "aMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4"
            + "RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n"
            + "50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM"
            + "2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQoj"
            + "oa7NQnAtw==";

    static final String[] PERMISSIONS = {
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED",
            "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER", "CONTROL_TV_SCREEN",
            "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
            "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE",
            "READ_COUNTRY_INFO", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
            "READ_SETTINGS"};

    static final String[] SIGNED_PERMISSIONS = {
            "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
            "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS",
            "WRITE_NOTIFICATION_ALERT", "CONTROL_POWER", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS",
            "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME"};

    Map<String, Object> registrationMsg(boolean signed) {
        Map<String, Object> manifest = Json.map("appVersion", "1.1", "manifestVersion", 1);
        if (signed) {
            manifest.put("permissions", Arrays.asList(PERMISSIONS));
            manifest.put("signatures", Arrays.asList(Json.map("signature", SIGNATURE, "signatureVersion", 1)));
            manifest.put("signed", Json.map(
                    "appId", "com.lge.test",
                    "created", "20140509",
                    "localizedAppNames", Json.map("", "LG Remote App", "ko-KR", "리모컨 앱", "zxx-XX", "ЛГ Rэмotэ AПП"),
                    "localizedVendorNames", Json.map("", "LG Electronics"),
                    "permissions", Arrays.asList(SIGNED_PERMISSIONS),
                    "serial", "2f930e2d2cfe083771f68e4fe7bb07",
                    "vendorId", "com.lge"));
        } else {
            TreeSet<String> all = new TreeSet<>(Arrays.asList(PERMISSIONS));
            all.addAll(Arrays.asList(SIGNED_PERMISSIONS));
            manifest.put("permissions", new ArrayList<>(all));
        }
        Map<String, Object> payload = Json.map("forcePairing", Boolean.FALSE, "manifest", manifest, "pairingType", "PROMPT");
        if (clientKey != null) payload.put("client-key", clientKey);
        return Json.map("type", "register", "id", "register_0", "payload", payload);
    }
}
