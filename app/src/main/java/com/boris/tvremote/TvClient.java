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
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
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
        exec.execute(() -> {
            boolean changed = this.ip == null || !this.ip.equals(ip);
            this.ip = ip;
            this.clientKey = (clientKey == null || clientKey.isEmpty()) ? null : clientKey;
            if (changed) dropConnections();
        });
    }

    public String getIp() { return ip; }

    public boolean isConnected() { WsConn m = main; return m != null && m.isOpen(); }

    /** Connect now (in background) so the first button press is instant. */
    public void connect() { exec.execute(() -> { try { ensureMain(); } catch (Exception e) { fail(e); } }); }

    public void shutdown() {
        exec.execute(this::dropConnections);
        exec.shutdown();
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
                listener.onLog("кнопка " + button);
            } catch (Exception e) {
                listener.onLog("кнопка " + button + " не прошла: " + e.getMessage());
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
                listener.onLog("ввод текста не прошёл (" + e.getMessage() + ") → ищу в YouTube");
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
        if (ip == null || ip.isEmpty()) throw new IOException("Не задан IP телевизора (⚙)");
        listener.onStatus("Подключаюсь к " + ip + "…", false);
        dropConnections();
        regQueue.clear();

        WsConn c = openMain();
        main = c;
        register(c);
        listener.onStatus("Подключено: " + ip, true);
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
                listener.onStatus("Нет связи с телевизором", false);
            }
        }
    };

    private void register(WsConn c) throws Exception {
        boolean signed = true;
        c.sendText(Json.stringify(registrationMsg(signed)));
        Map<String, Object> r = regQueue.poll(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (r == null) throw new IOException("Телевизор не ответил на регистрацию");
        if ("error".equals(r.get("type"))) {
            // Newer firmware rejects the signed manifest: retry unsigned.
            signed = false;
            c.sendText(Json.stringify(registrationMsg(signed)));
            r = regQueue.poll(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (r == null) throw new IOException("Телевизор не ответил на регистрацию");
        }
        Map<String, Object> p = Json.obj(r.get("payload"));
        if ("response".equals(r.get("type")) && p != null && "PROMPT".equals(p.get("pairingType"))) {
            listener.onStatus("Нажмите «Принять» на экране телевизора (пультом)", false);
            r = regQueue.poll(PAIR_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (r == null) throw new IOException("Не подтвердили на телевизоре за 60 секунд");
            p = Json.obj(r.get("payload"));
        }
        if ("error".equals(r.get("type"))) throw new IOException("Телевизор отказал: " + r.get("error"));
        if (!"registered".equals(r.get("type"))) throw new IOException("Неожиданный ответ: " + r.get("type"));
        String key = p == null ? null : Json.str(p.get("client-key"));
        if (key != null && !key.isEmpty() && !key.equals(clientKey)) {
            clientKey = key;
            listener.onClientKey(key);
        }
    }

    private Map<String, Object> request(String uri, Map<String, Object> payload) throws Exception {
        ensureMain();
        String id = "req_" + seq.getAndIncrement();
        BlockingQueue<Map<String, Object>> q = new ArrayBlockingQueue<>(2);
        pending.put(id, q);
        try {
            Map<String, Object> msg = Json.map("id", id, "type", "request", "uri", "ssap://" + uri);
            if (payload != null) msg.put("payload", payload);
            main.sendText(Json.stringify(msg));
            Map<String, Object> r = q.poll(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (r == null) { listener.onLog(uri + " → нет ответа"); throw new IOException("Телевизор не ответил"); }
            listener.onLog(uri + " → " + abbreviate(Json.stringify(r.get("type")) + " " + Json.stringify(r.containsKey("error") ? r.get("error") : r.get("payload"))));
            if ("error".equals(r.get("type"))) throw new IOException(String.valueOf(r.get("error")));
            Map<String, Object> p = Json.obj(r.get("payload"));
            if (p != null && Boolean.FALSE.equals(p.get("returnValue"))) {
                throw new IOException(String.valueOf(p.containsKey("errorText") ? p.get("errorText") : "ошибка команды"));
            }
            return p;
        } finally {
            pending.remove(id);
        }
    }

    private WsConn ensureInput() throws Exception {
        WsConn in = input;
        if (in == null || !in.isOpen()) {
            Map<String, Object> r = request("com.webos.service.networkinput/getPointerInputSocket", null);
            String path = r == null ? null : Json.str(r.get("socketPath"));
            if (path == null) throw new IOException("Нет сокета кнопок");
            in = openInput(path);
            input = in;
        }
        return in;
    }

    private void sendInput(String msg) throws Exception {
        ensureInput().sendText(msg);
    }

    /** Open the pointer/key socket ahead of time so the touchpad reacts instantly. */
    public void prepareInput() {
        exec.execute(() -> { try { ensureInput(); } catch (Exception e) { listener.onLog("сокет мыши: " + e.getMessage()); } });
    }

    private volatile long lastPointerError;

    private void pointer(String msg) {
        exec.execute(() -> {
            try {
                sendInput(msg);
            } catch (Exception e) {
                closeInput();
                long now = System.currentTimeMillis();
                if (now - lastPointerError > 3000) { lastPointerError = now; fail(e); }
            }
        });
    }

    /** Move the TV's on-screen pointer (Magic Remote style). */
    public void move(int dx, int dy) { pointer("type:move\ndx:" + dx + "\ndy:" + dy + "\ndown:0\n\n"); }
    public void click() { pointer("type:click\n\n"); listener.onLog("клик мышью"); }
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
