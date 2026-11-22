package com.boris.tvremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements TvClient.Listener {

    private static final int REQ_VOICE_INTENT = 101;
    private static final int REQ_MIC = 201;

    // "Classic" palette
    private static final int C_BG = 0xFF111418;
    private static final int C_KEY = 0xFF23272E;
    private static final int C_TEXT = 0xFFF2F4F7;
    private static final int C_DIM = 0xFF8A93A0;
    private static final int C_LABEL = 0xFFA9B1BD;
    private static final int C_OK = 0xFF3B82F6;
    private static final int C_POWER = 0xFFC2362F;
    private static final int C_PAD = 0xFF181B21;
    private static final int C_PAD_EDGE = 0xFF2E3440;
    private static final int C_APP_BG = 0xFF2A1F1F;
    private static final int C_APP_EDGE = 0xFF5A2B2B;
    private static final int C_APP_TEXT = 0xFFFF8A80;

    private SharedPreferences prefs;
    private TvClient tv;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusLine, tvName, logView;
    private TextView segButtons, segMouse, langRu, langEn;
    private View dpad, pad;
    private View padDot;
    private TextView flashView;
    private TvPreview preview;
    private TextView previewTip;
    private View playRow, headerRow, navRow, segRowView, rockerRow, appsRow, focusBar;
    private TextView eyeSeg, eyeFocus;
    private boolean shotInFlight;
    private long lastTouchAt;
    private float curX = TvPreview.TV_W / 2, curY = TvPreview.TV_H / 2;
    private View padHints;
    private boolean resumed;
    private String infoText = "";
    private SpeechRecognizer recognizer;
    private boolean voiceToYoutube;
    private boolean connected;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        L.en = "en".equals(prefs.getString("ui", "ru"));
        // Start-up look Boris chose: mouse mode, full remote visible, TV screen preview off.
        prefs.edit().putString("mode", "mouse").putBoolean("preview", false).apply();
        tv = new TvClient(this);
        setContentView(buildUi());
        setMode(prefs.getString("mode", "buttons"));
        if (ip().isEmpty()) ui.postDelayed(this::firstRunDiscover, 400);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ip().isEmpty()) {
            tv.setTarget(ip(), prefs.getString("key", ""));
            tv.connect();
            if ("mouse".equals(prefs.getString("mode", ""))) tv.prepareInput();
        }
        resumed = true;
        ui.removeCallbacks(infoPoll);
        ui.removeCallbacks(shotPoll);
        ui.postDelayed(infoPoll, 1500);
        ui.postDelayed(shotPoll, 1800);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(infoPoll);
        ui.removeCallbacks(shotPoll);
        super.onPause();
    }

    // ---------------- live feedback from the TV ----------------

    private final Runnable infoPoll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (connected) tv.requestInfo((app, vol, mute) -> ui.post(() -> showInfo(app, vol, mute)));
            ui.postDelayed(this, 2500);
        }
    };

    private final Runnable shotPoll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            boolean touching = SystemClock.uptimeMillis() - lastTouchAt < 3000;
            if (connected && previewOn() && pad != null && pad.getVisibility() == View.VISIBLE && !shotInFlight && !aiming) {
                shotInFlight = true;
                tv.requestScreenshot(jpeg -> ui.post(() -> { shotInFlight = false; showShot(jpeg); }));
            }
            ui.postDelayed(this, touching ? 700 : 1500);
        }
    };

    private boolean previewOn() { return prefs.getBoolean("preview", true); }

    private void showShot(byte[] jpeg) {
        if (preview == null) return;
        if (jpeg == null) {
            if (!preview.hasBitmap()) preview.setMessage(L.s("Нет картинки с телевизора — см. журнал 📋", "No picture from the TV — see log 📋"));
            return;
        }
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = 2;   // a phone-sized preview is plenty
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (b == null) return;
        if (isBlack(b)) {
            // keep the last good frame instead of flashing black
            if (!preview.hasBitmap()) preview.setMessage(L.s("ТВ отдаёт чёрный кадр (так бывает в фильмах)", "TV returns a black frame (common in movies)"));
            return;
        }
        preview.setBitmap(b);
    }

    private static boolean isBlack(android.graphics.Bitmap b) {
        long sum = 0;
        int n = 0;
        for (int y = 1; y < 12; y++) {
            for (int x = 1; x < 12; x++) {
                int c = b.getPixel(b.getWidth() * x / 12, b.getHeight() * y / 12);
                sum += ((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff);
                n += 3;
            }
        }
        return n > 0 && sum / n < 12;
    }

    private static String appName(String id) {
        if (id == null || id.isEmpty()) return null;
        if (id.startsWith("youtube")) return "YouTube";
        if (id.equals("netflix")) return "Netflix";
        if (id.equals("amazon")) return "Prime Video";
        if (id.contains("disney")) return "Disney+";
        if (id.contains("hulu")) return "Hulu";
        if (id.contains("max") && id.contains("hbo")) return "Max";
        if (id.startsWith("com.webos.app.hdmi")) return "HDMI " + id.substring("com.webos.app.hdmi".length());
        if (id.equals("com.webos.app.livetv")) return L.s("Эфирное ТВ", "Live TV");
        if (id.contains("home") || id.contains("launcher")) return L.s("Главный экран", "Home screen");
        if (id.contains("miracast") || id.contains("screenshare")) return L.s("Трансляция экрана", "Screen share");
        if (id.contains("browser")) return L.s("Браузер", "Browser");
        if (id.contains("settings")) return L.s("Настройки ТВ", "TV settings");
        String[] parts = id.split("\\.");
        String last = parts[parts.length - 1];
        return last.isEmpty() ? id : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    private void showInfo(String app, Integer vol, Boolean mute) {
        currentApp = app == null ? "" : app;
        StringBuilder b = new StringBuilder();
        String name = appName(app);
        if (name != null) b.append(" · ").append(name);
        if (Boolean.TRUE.equals(mute)) b.append(" · 🔇");
        else if (vol != null) b.append(" · 🔊 ").append(vol);
        infoText = b.toString();
        if (connected) statusLine.setText(L.s("Подключено", "Connected") + infoText);
    }

    /** Short on-screen confirmation of what was just sent to the TV. */
    private void flash(String what) {
        if (flashView == null || what == null) return;
        flashView.setText(what);
        flashView.animate().cancel();
        flashView.setAlpha(1f);
        flashView.setVisibility(View.VISIBLE);
        flashView.animate().alpha(0f).setStartDelay(900).setDuration(400).start();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        tv.shutdown();
        super.onDestroy();
    }

    private String ip() { return prefs.getString("ip", ""); }
    private String lang() { return prefs.getString("lang", "ru-RU"); }

    // =====================================================================
    // UI
    // =====================================================================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(16), dp(10), dp(16), dp(14));

        // --- header: status / name · log · settings · power
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        statusLine = text(L.s("Нет связи", "Not connected"), 12, C_DIM, false);
        tvName = text(ip().isEmpty() ? L.s("Телевизор не настроен", "TV not set up") : "LG · " + ip(), 16, C_TEXT, true);
        tvName.setSingleLine(true);
        names.addView(statusLine);
        names.addView(tvName);
        header.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(key(R.drawable.ic_list, null, 0, C_DIM, -1, 22, L.s("Журнал", "Log"),
                v -> logView.setVisibility(logView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), size(44, 44));
        header.addView(key(R.drawable.ic_cog, null, 0, C_DIM, -1, 22, L.s("Настройки пульта", "Remote settings"), v -> showSettings()), size(44, 44));
        View power = key(R.drawable.ic_power, null, C_POWER, Color.WHITE, -1, 24, L.s("Питание", "Power"), v -> powerToggle());
        power.setOnLongClickListener(v -> { haptic(v); powerOn(); return true; });
        header.addView(power, margins(size(52, 52), 8, 0, 0, 0));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));
        headerRow = header;

        // --- back · home · mute · menu · keyboard
        LinearLayout nav = row();
        nav.addView(key(R.drawable.ic_back, null, C_KEY, C_TEXT, 26, 22, L.s("Назад", "Back"), v -> tv.button("BACK")), weightLp(0));
        nav.addView(key(R.drawable.ic_home, null, C_KEY, C_TEXT, 26, 22, L.s("Домой", "Home"), v -> tv.button("HOME")), weightLp(8));
        nav.addView(key(R.drawable.ic_mute, null, C_KEY, C_TEXT, 26, 22, L.s("Без звука", "Mute"), v -> tv.toggleMute()), weightLp(8));
        nav.addView(key(R.drawable.ic_menu, null, C_KEY, C_TEXT, 26, 22, L.s("Меню телевизора", "TV menu"), v -> tv.button("MENU")), weightLp(8));
        nav.addView(key(R.drawable.ic_kbd, null, C_KEY, C_TEXT, 26, 22, L.s("Клавиатура", "Keyboard"), v -> showKeyboard()), weightLp(8));
        root.addView(nav, margins(new LinearLayout.LayoutParams(-1, dp(52)), 0, 10, 0, 0));
        navRow = nav;

        // --- segmented: Кнопки | Мышь
        LinearLayout seg = row();
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackground(round(C_KEY, 22));
        segButtons = segItem(L.s("Кнопки", "Buttons"), v -> setMode("buttons"));
        segMouse = segItem(L.s("Мышь", "Mouse"), v -> setMode("mouse"));
        seg.addView(segButtons, size(112, 36));
        seg.addView(segMouse, margins(size(112, 36), 4, 0, 0, 0));
        LinearLayout segRow = row();
        segRow.setGravity(Gravity.CENTER);
        segRow.addView(seg);
        eyeSeg = eyeToggle();
        segRow.addView(eyeSeg, margins(new LinearLayout.LayoutParams(-2, dp(44)), 10, 0, 0, 0));
        root.addView(segRow, margins(new LinearLayout.LayoutParams(-1, -2), 0, 14, 0, 0));
        segRowView = segRow;

        // --- compact bar shown only in "mouse + TV screen" focus mode
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView exit = segItem("◀  " + L.s("Кнопки", "Buttons"), v -> setMode("buttons"));
        exit.setBackground(round(C_KEY, 20));
        exit.setPadding(dp(12), 0, dp(12), 0);
        bar.addView(exit, new LinearLayout.LayoutParams(-2, dp(44)));
        bar.addView(key(R.drawable.ic_back, null, C_KEY, C_TEXT, 22, 20, L.s("Назад", "Back"), v -> tv.button("BACK")),
                margins(size(50, 44), 6, 0, 0, 0));
        bar.addView(key(R.drawable.ic_home, null, C_KEY, C_TEXT, 22, 20, L.s("Домой", "Home"), v -> tv.button("HOME")),
                margins(size(50, 44), 6, 0, 0, 0));
        TextView cal = segItem("🎯", v -> autoCalibrate());
        cal.setBackground(round(C_KEY, 20));
        cal.setContentDescription(L.s("Калибровка курсора", "Calibrate cursor"));
        bar.addView(cal, margins(size(48, 44), 6, 0, 0, 0));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        eyeFocus = eyeToggle();
        eyeFocus.setContentDescription(L.s("Экран ТВ: показать / скрыть", "TV screen: show / hide"));
        bar.addView(eyeFocus, margins(new LinearLayout.LayoutParams(-2, dp(44)), 6, 0, 0, 0));
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        focusBar = bar;

        // --- center (fills the free space): d-pad or touchpad; the log overlays it
        FrameLayout center = new FrameLayout(this);
        DpadView dv = new DpadView(this, C_KEY, C_OK, C_TEXT);
        dv.setOnKey(name -> {
            tv.button(name);
            switch (name) {
                case "UP": flash("▲ " + L.s("Вверх", "Up")); break;
                case "DOWN": flash("▼ " + L.s("Вниз", "Down")); break;
                case "LEFT": flash("◀ " + L.s("Влево", "Left")); break;
                case "RIGHT": flash("▶ " + L.s("Вправо", "Right")); break;
                default: flash("OK");
            }
        });
        dpad = dv;
        pad = buildPad();
        center.addView(dpad, new FrameLayout.LayoutParams(-1, -1));
        center.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        logView = text(L.s("Журнал пуст", "Log is empty"), 11, 0xFF9FD3A8, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackground(round(0xF00A0C0F, 16));
        logView.setPadding(dp(10), dp(8), dp(10), dp(8));
        logView.setVerticalScrollBarEnabled(true);
        logView.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        logView.setVisibility(View.GONE);
        center.addView(logView, new FrameLayout.LayoutParams(-1, -1));
        flashView = text("", 15, C_TEXT, true);
        flashView.setBackground(round(0xE6000000, 18));
        flashView.setPadding(dp(16), dp(8), dp(16), dp(8));
        flashView.setAlpha(0f);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        flp.topMargin = dp(6);
        center.addView(flashView, flp);
        root.addView(center, margins(new LinearLayout.LayoutParams(-1, 0, 1f), 0, 14, 0, 14));

        // --- VOL rocker · mic + language · CH rocker
        LinearLayout rockers = row();
        rockers.setGravity(Gravity.CENTER_VERTICAL);
        rockers.addView(rocker("VOL", R.drawable.ic_plus, R.drawable.ic_minus, L.s("Громче", "Volume up"), L.s("Тише", "Volume down"),
                () -> tv.volumeUp(), () -> tv.volumeDown(), true), new LinearLayout.LayoutParams(dp(72), -2));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        View mic = key(R.drawable.ic_mic, null, C_TEXT, C_BG, -1, 34, L.s("Голосовой поиск", "Voice search"), v -> voice(false));
        mic.setOnLongClickListener(v -> { haptic(v); voice(true); return true; });
        mid.addView(mic, size(84, 84));
        LinearLayout langSeg = row();
        langSeg.setPadding(dp(3), dp(3), dp(3), dp(3));
        langSeg.setBackground(round(C_KEY, 18));
        langRu = segItem("RU", v -> setLang("ru-RU"));
        langEn = segItem("EN", v -> setLang("en-US"));
        langSeg.addView(langRu, size(52, 32));
        langSeg.addView(langEn, margins(size(52, 32), 3, 0, 0, 0));
        mid.addView(langSeg, margins(new LinearLayout.LayoutParams(-2, -2), 0, 12, 0, 0));
        rockers.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        rockers.addView(rocker("CH", R.drawable.ic_up, R.drawable.ic_down, L.s("Канал вверх", "Channel up"), L.s("Канал вниз", "Channel down"),
                () -> tv.channelUp(), () -> tv.channelDown(), false), new LinearLayout.LayoutParams(dp(72), -2));
        root.addView(rockers, new LinearLayout.LayoutParams(-1, -2));
        rockerRow = rockers;
        setLang(lang());

        // --- playback
        LinearLayout play = row();
        play.addView(key(R.drawable.ic_rew, null, C_KEY, C_TEXT, 14, 22, L.s("Перемотка назад", "Rewind"), v -> tv.button("REWIND")), weightLp(0));
        play.addView(key(R.drawable.ic_playpause, null, C_KEY, C_TEXT, 14, 22, L.s("Пауза / воспроизведение", "Play / pause"), v -> playPause()), weightLp(8));
        play.addView(key(R.drawable.ic_ff, null, C_KEY, C_TEXT, 14, 22, L.s("Перемотка вперёд", "Fast forward"), v -> tv.button("FASTFORWARD")), weightLp(8));
        root.addView(play, margins(new LinearLayout.LayoutParams(-1, dp(46)), 0, 14, 0, 0));
        playRow = play;

        // --- apps
        LinearLayout apps = row();
        apps.addView(appKey("YouTube", true, v -> tv.launch("youtube.leanback.v4")), weightLp(0));
        apps.addView(appKey("Netflix", true, v -> tv.launch("netflix")), weightLp(8));
        apps.addView(appKey(L.s("🌐 Сайт", "🌐 Web"), false, v -> showBrowser()), weightLp(8));
        apps.addView(appKey("HDMI", false, v -> showInputs()), weightLp(8));
        apps.addView(appKey("123", false, v -> showKeypad()), weightLp(8));
        root.addView(apps, margins(new LinearLayout.LayoutParams(-1, dp(46)), 0, 10, 0, 0));
        appsRow = apps;
        return root;
    }

    // ---------------- touchpad ----------------

    private View buildPad() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        preview = new TvPreview(this);
        preview.setMessage(L.s("Экран телевизора появится здесь", "The TV screen will appear here"));
        box.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1f));
        attachPreviewGestures();
        TextView tip = text(L.s("Картинка: касание — курсор туда · двойное — клик · 🎯 калибровка",
                "Picture: tap — cursor there · double tap — click · 🎯 calibrate"), 11, C_DIM, false);
        tip.setGravity(Gravity.CENTER);
        box.addView(tip, margins(new LinearLayout.LayoutParams(-1, -2), 0, 4, 0, 0));
        previewTip = tip;

        FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = round(C_PAD, 24);
        bg.setStroke(dp(1), C_PAD_EDGE);
        f.setBackground(bg);

        padDot = new View(this);
        padDot.setBackground(oval(C_OK));
        padDot.setAlpha(0f);
        f.addView(padDot, new FrameLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.VERTICAL);
        hints.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView cur = new ImageView(this);
        cur.setImageResource(R.drawable.ic_cursor);
        cur.setColorFilter(0xFF3A414C, PorterDuff.Mode.SRC_IN);
        hints.addView(cur, size(32, 32));
        TextView h1 = text(L.s("Водите пальцем — курсор на ТВ", "Move your finger — cursor on TV"), 14, 0xFFC9CED6, true);
        TextView h2 = text(L.s("касание — клик · двумя пальцами — стрелки (листать)", "tap — click · two fingers — arrows (scroll)"), 12, C_DIM, false);
        h1.setGravity(Gravity.CENTER);
        h2.setGravity(Gravity.CENTER);
        hints.addView(h1, margins(new LinearLayout.LayoutParams(-2, -2), 0, 6, 0, 0));
        hints.addView(h2, margins(new LinearLayout.LayoutParams(-2, -2), 0, 2, 0, 0));
        f.addView(hints, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        padHints = hints;
        f.setOnTouchListener(new PadTouch());

        // Arrow keys on the touchpad edges (YouTube rows etc. move with arrows). Held = repeat.
        Object[][] edges = {
                {R.drawable.ic_up, "UP", Gravity.TOP | Gravity.CENTER_HORIZONTAL, 64, 40},
                {R.drawable.ic_down, "DOWN", Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 64, 40},
                {R.drawable.ic_left, "LEFT", Gravity.START | Gravity.CENTER_VERTICAL, 40, 64},
                {R.drawable.ic_right, "RIGHT", Gravity.END | Gravity.CENTER_VERTICAL, 40, 64}};
        for (Object[] ed : edges) {
            final String k = (String) ed[1];
            FrameLayout arrowKey = key((Integer) ed[0], null, 0xCC23272E, C_TEXT, 14, 24, arrowLabel(k), v -> { });
            holdArrow(arrowKey, k);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp((Integer) ed[3]), dp((Integer) ed[4]), (Integer) ed[2]);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            f.addView(arrowKey, lp);
        }
        box.addView(f, margins(new LinearLayout.LayoutParams(-1, 0, 1.25f), 0, 10, 0, 0));
        applyPreviewSetting();
        return box;
    }

    private void applyPreviewSetting() {
        // The picture area always keeps its place, so the touchpad below has the same size and position
        // whether the TV screen is shown or not.
        if (preview != null) {
            preview.setVisibility(View.VISIBLE);
            if (!previewOn()) {
                preview.setBitmap(null);
                preview.setCursor(0, 0, false);
                preview.setMessage(L.s("Экран ТВ выключен · 👁 — включить", "TV screen off · 👁 — turn on"));
            }
        }
        if (previewTip != null) previewTip.setVisibility(previewOn() ? View.VISIBLE : View.INVISIBLE);
        applyLayout();
    }

    /** Mouse + TV screen = focus mode: only the compact bar, the TV picture and the touchpad. */
    private void applyLayout() {
        boolean mouse = "mouse".equals(prefs.getString("mode", "buttons"));
        boolean focus = mouse;   // mouse mode always: compact bar, picture area, touchpad at the bottom
        int normal = focus ? View.GONE : View.VISIBLE;
        for (View v : new View[]{headerRow, navRow, segRowView, rockerRow, appsRow, playRow}) {
            if (v != null) v.setVisibility(normal);
        }
        if (focusBar != null) focusBar.setVisibility(focus ? View.VISIBLE : View.GONE);
        if (eyeSeg != null) eyeSeg.setVisibility(mouse && !focus ? View.VISIBLE : View.GONE);
        if (eyeSeg != null) eyeSeg.setText(previewOn() ? L.s("👁 Экран ТВ: вкл", "👁 TV screen: on") : L.s("👁 Экран ТВ: выкл", "👁 TV screen: off"));
        if (eyeFocus != null) eyeFocus.setText(previewOn() ? "👁 ✓" : "👁 ✕");
    }

    private TextView eyeToggle() {
        TextView t = segItem("", v -> {
            boolean on = !previewOn();
            prefs.edit().putBoolean("preview", on).apply();
            applyPreviewSetting();
            if (on && "mouse".equals(prefs.getString("mode", ""))) { tv.resetScreenshot(); syncCursor(); }
        });
        t.setBackground(round(C_KEY, 20));
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return t;
    }

    /** Park the TV pointer in the top-left corner (it stops at the edge), then bring it to the centre, so the drawn cursor matches. */
    // Mapping from "units sent after parking" to TV-picture pixels: px = o + k * units (per axis).
    private float kx() { return prefs.getFloat("p2kx", 1f); }
    private float ky() { return prefs.getFloat("p2ky", 1f); }
    private float ox() { return prefs.getFloat("p2ox", 0f); }
    private float oy() { return prefs.getFloat("p2oy", 0f); }
    private int unitsX(float px) { return Math.round((px - ox()) / kx()); }
    private int unitsY(float py) { return Math.round((py - oy()) / ky()); }

    /** Put the TV pointer exactly at a point of the picture (and click if asked). */
    private void pointAt(float px, float py, boolean click) {
        lastPointerAt = SystemClock.uptimeMillis();
        tv.pointTo(unitsX(px), unitsY(py), click);
        curX = px;
        curY = py;
        if (preview != null) preview.setCursor(curX, curY, true);
    }

    /** Re-anchor: park and bring the pointer to the centre of the screen. */
    private void syncCursor() {
        pointAt(TvPreview.TV_W / 2, TvPreview.TV_H / 2, false);
    }

    // Two-point calibration. Point 1 is close to the corner (always on screen); from it the pointer speed is
    // estimated, and point 2 is chosen so it lands at ~70% of the screen — never off the edge.
    private static final int[] CAL_FIRST = {200, 120};
    private int calStep = -1;
    private final int[][] calUnits = new int[2][];
    private final float[][] calTaps = new float[2][];

    /** LG hides an idle pointer after a few seconds — nudge it back and forth while we wait for a tap. */
    private final Runnable keepPointerAwake = new Runnable() {
        @Override public void run() {
            if (calStep < 0) return;
            tv.nudge(1, 0);
            tv.nudge(-1, 0);
            ui.postDelayed(this, 1500);
        }
    };

    private void startCalibration() {
        if (preview == null) return;
        calStep = 0;
        calUnits[0] = CAL_FIRST;
        tv.pointTo(CAL_FIRST[0], CAL_FIRST[1], false);
        preview.setCursor(0, 0, false);
        ui.removeCallbacks(keepPointerAwake);
        ui.postDelayed(keepPointerAwake, 1500);
        String m = L.s("Калибровка 1 из 2: курсор слева вверху — коснитесь картинки там, где он на ТВ",
                "Calibration 1 of 2: the pointer is top-left — tap the picture where it is on the TV");
        flash(m);
        toast(m);
    }

    private void calibrationTap(float[] t) {
        calTaps[calStep] = t;
        if (calStep == 0) {
            // rough speed from point 1 (offset assumed small), then aim point 2 at ~70% of the screen
            float kx0 = Math.max(0.3f, Math.min(6f, t[0] / CAL_FIRST[0]));
            float ky0 = Math.max(0.3f, Math.min(6f, t[1] / CAL_FIRST[1]));
            calUnits[1] = new int[]{Math.round(TvPreview.TV_W * 0.70f / kx0), Math.round(TvPreview.TV_H * 0.70f / ky0)};
            calStep = 1;
            tv.pointTo(calUnits[1][0], calUnits[1][1], false);
            String m = L.s("Калибровка 2 из 2: курсор справа внизу — снова коснитесь, где он на ТВ",
                    "Calibration 2 of 2: the pointer is bottom-right — tap again where it is on the TV");
            flash(m);
            toast(m);
            return;
        }
        calStep = -1;
        ui.removeCallbacks(keepPointerAwake);
        float du = calUnits[1][0] - calUnits[0][0], dv = calUnits[1][1] - calUnits[0][1];
        float kx = (calTaps[1][0] - calTaps[0][0]) / du, ky = (calTaps[1][1] - calTaps[0][1]) / dv;
        if (du < 50 || dv < 50 || kx < 0.1f || ky < 0.1f || kx > 10f || ky > 10f) {
            toast(L.s("Калибровка не удалась — попробуйте ещё раз 🎯", "Calibration failed — try 🎯 again"));
            return;
        }
        float ox = calTaps[0][0] - kx * calUnits[0][0], oy = calTaps[0][1] - ky * calUnits[0][1];
        prefs.edit().putFloat("p2kx", kx).putFloat("p2ky", ky).putFloat("p2ox", ox).putFloat("p2oy", oy).apply();
        samples.clear();
        samples.add(new float[]{calUnits[0][0], calUnits[0][1], calTaps[0][0], calTaps[0][1]});
        samples.add(new float[]{calUnits[1][0], calUnits[1][1], calTaps[1][0], calTaps[1][1]});
        log(String.format(java.util.Locale.US, "калибровка: kx=%.3f ky=%.3f ox=%.0f oy=%.0f", kx, ky, ox, oy));
        curX = calTaps[1][0];
        curY = calTaps[1][1];
        preview.setCursor(curX, curY, true);
        flash(L.s("Готово ✓ Теперь касайтесь картинки — курсор пойдёт туда", "Done ✓ Now tap the picture — the cursor goes there"));
    }

    // ---------------- closed-loop aiming: move, look at a screenshot, find the pointer, correct ----------------

    private volatile boolean aiming;
    private volatile boolean lastGrabBlack;
    private volatile Boolean pointerInShots;   // null = not known yet
    private int findMisses;
    private final ArrayList<float[]> samples = new ArrayList<>();   // {ux, uy, px, py} measured from the corner

    /** Blocking screenshot → luminance grid, or null. Must not run on the UI thread. */
    private int[] grabGrid() {
        final java.util.concurrent.CompletableFuture<byte[]> f = new java.util.concurrent.CompletableFuture<>();
        tv.requestScreenshot(f::complete);
        byte[] jpeg;
        try { jpeg = f.get(6, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { return null; }
        if (jpeg == null) return null;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = 2;
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        lastGrabBlack = b != null && isBlack(b);
        if (b == null || lastGrabBlack) return null;
        ui.post(() -> { if (preview != null) preview.setBitmap(b); });
        android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(b, CursorFinder.GW, CursorFinder.GH, true);
        int[] px = new int[CursorFinder.GW * CursorFinder.GH];
        s.getPixels(px, 0, CursorFinder.GW, 0, 0, CursorFinder.GW, CursorFinder.GH);
        return CursorFinder.luminance(px);
    }

    private static void sleepQ(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    private void addSample(int ux, int uy, float px, float py) {
        samples.add(new float[]{ux, uy, px, py});
        while (samples.size() > 12) samples.remove(0);
        int n = samples.size();
        float[] u1 = new float[n], p1 = new float[n], u2 = new float[n], p2 = new float[n];
        for (int i = 0; i < n; i++) { float[] s = samples.get(i); u1[i] = s[0]; u2[i] = s[1]; p1[i] = s[2]; p2[i] = s[3]; }
        float[] fx = CursorFinder.fit(u1, p1, n), fy = CursorFinder.fit(u2, p2, n);
        SharedPreferences.Editor e = prefs.edit();
        if (fx != null) e.putFloat("p2kx", fx[0]).putFloat("p2ox", fx[1]);
        else e.putFloat("p2ox", px - kx() * ux);          // one sample: correct the offset only
        if (fy != null) e.putFloat("p2ky", fy[0]).putFloat("p2oy", fy[1]);
        else e.putFloat("p2oy", py - ky() * uy);
        e.apply();
        ui.post(() -> log(String.format(java.util.Locale.US, "замер курсора: %.0f,%.0f → kx=%.3f ky=%.3f", px, py, kx(), ky())));
    }

    /** Point at (tx, ty) of the picture; with the TV picture on, measure the real pointer and correct it. */
    private void aimAt(float tx, float ty, boolean click) {
        if (aiming) return;
        flash(click ? L.s("Клик сюда…", "Click here…") : L.s("Курсор сюда…", "Cursor here…"));
        if (!previewOn() || Boolean.FALSE.equals(pointerInShots)) { pointAt(tx, ty, click); return; }
        aiming = true;
        new Thread(() -> {
            try {
                int ux = unitsX(tx), uy = unitsY(ty);
                int[] before = grabGrid();
                tv.pointTo(ux, uy, false).get(15, java.util.concurrent.TimeUnit.SECONDS);
                sleepQ(300);
                int[] after = grabGrid();
                float[] p = CursorFinder.find(before, after, tx, ty, 500);
                if (p == null) {
                    if (++findMisses >= 2 && before != null && after != null) {
                        pointerInShots = false;
                        ui.post(() -> {
                            log(L.s("курсор LG не виден на снимках экрана — точная доводка невозможна",
                                    "LG pointer is not visible in screenshots — exact correction impossible"));
                            toast(L.s("Телевизор не показывает курсор на снимках — наводка будет примерной",
                                    "The TV hides the pointer in screenshots — aiming will be approximate"));
                        });
                    }
                } else {
                    pointerInShots = true;
                    findMisses = 0;
                    addSample(ux, uy, p[0], p[1]);
                    int[] last = after;
                    for (int it = 0; it < 2; it++) {
                        float ex = tx - p[0], ey = ty - p[1];
                        if (Math.hypot(ex, ey) < 14) break;
                        tv.nudge(Math.round(ex / kx()), Math.round(ey / ky())).get(10, java.util.concurrent.TimeUnit.SECONDS);
                        sleepQ(300);
                        int[] g = grabGrid();
                        float[] q = CursorFinder.find(last, g, tx, ty, 250);
                        if (q == null) break;
                        p = q;
                        last = g;
                    }
                    final float fx = p[0], fy = p[1];
                    ui.post(() -> { curX = fx; curY = fy; if (preview != null) preview.setCursor(fx, fy, true); });
                }
                if (click) tv.click();
            } catch (Exception e) {
                ui.post(() -> log("aim: " + e));
            } finally {
                aiming = false;
            }
        }).start();
        curX = tx;
        curY = ty;
        if (preview != null) preview.setCursor(tx, ty, true);
    }

    /** 🎯: measure the pointer at three spots automatically; if the TV hides it, fall back to tap calibration. */
    private void autoCalibrate() {
        if (aiming || preview == null) return;
        if (!previewOn() || Boolean.FALSE.equals(pointerInShots)) { startCalibration(); return; }
        aiming = true;
        flash(L.s("Проверяю картинку…", "Checking the picture…"));
        new Thread(() -> {
            int[] first = grabGrid();
            aiming = false;
            final boolean black = first == null && lastGrabBlack;
            final boolean none = first == null && !lastGrabBlack;
            ui.post(() -> {
                if (black) {
                    new AlertDialog.Builder(this)
                            .setTitle(L.s("Картинка защищена", "Picture is protected"))
                            .setMessage(L.s("Сейчас на телевизоре приложение с защитой (фильм, Disney+, Netflix…) — снимок экрана чёрный, курсор не видно.\n\nПерейти на главный экран и откалибровать там? Фильм можно будет открыть снова.",
                                    "A protected app is on the TV (movie, Disney+, Netflix…) — the screenshot is black, the pointer can't be seen.\n\nGo to the Home screen and calibrate there? You can reopen the movie after."))
                            .setPositiveButton(L.s("Да, на главный", "Yes, go Home"), (d, w) -> {
                                tv.button("HOME");
                                flash(L.s("Жду главный экран…", "Waiting for Home…"));
                                ui.postDelayed(this::runAutoCalibration, 3000);
                            })
                            .setNegativeButton(L.s("Отмена", "Cancel"), null)
                            .show();
                } else if (none) {
                    toast(L.s("Телевизор не отдал снимок экрана — подробности в журнале 📋", "The TV gave no screenshot — see log 📋"));
                } else {
                    runAutoCalibration();
                }
            });
        }).start();
    }

    private void runAutoCalibration() {
        if (aiming) return;
        aiming = true;
        flash(L.s("Калибрую сам, не трогайте 5–10 секунд…", "Calibrating, hands off for 5–10 s…"));
        samples.clear();
        new Thread(() -> {
            int found = 0;
            try {
                int[] prev = grabGrid();
                java.util.List<int[]> pts = new java.util.ArrayList<>();
                pts.add(new int[]{CAL_FIRST[0], CAL_FIRST[1]});
                for (int idx = 0; idx < 3; idx++) {
                    if (idx == 1 && samples.size() > 0) {
                        // speed estimate from the first measurement → keep the next points on screen
                        float[] s0 = samples.get(samples.size() - 1);
                        float kx0 = Math.max(0.3f, Math.min(6f, s0[2] / Math.max(1f, s0[0])));
                        float ky0 = Math.max(0.3f, Math.min(6f, s0[3] / Math.max(1f, s0[1])));
                        pts.add(new int[]{Math.round(TvPreview.TV_W * 0.70f / kx0), Math.round(TvPreview.TV_H * 0.70f / ky0)});
                        pts.add(new int[]{Math.round(TvPreview.TV_W * 0.40f / kx0), Math.round(TvPreview.TV_H * 0.45f / ky0)});
                    }
                    if (idx >= pts.size()) break;
                    int[] u = pts.get(idx);
                    tv.pointTo(u[0], u[1], false).get(15, java.util.concurrent.TimeUnit.SECONDS);
                    sleepQ(350);
                    int[] g = grabGrid();
                    float ex = ox() + kx() * u[0], ey = oy() + ky() * u[1];
                    float[] p = CursorFinder.find(prev, g, ex, ey, 900);
                    final String line = "🎯 " + u[0] + "," + u[1] + " → " + (p == null ? L.s("курсор не найден", "pointer not found")
                            : String.format(java.util.Locale.US, "%.0f,%.0f", p[0], p[1]));
                    ui.post(() -> log(line));
                    if (p != null) { addSample(u[0], u[1], p[0], p[1]); found++; }
                    if (g != null) prev = g;
                    if (idx == 0 && p == null) break;   // can't see it near the corner → no point going on
                }
            } catch (Exception e) {
                ui.post(() -> log("calib: " + e));
            } finally {
                aiming = false;
            }
            final int f = found;
            ui.post(() -> {
                if (f >= 2) {
                    pointerInShots = true;
                    flash(L.s("Готово ✓ курсор откалиброван автоматически", "Done ✓ pointer calibrated automatically"));
                } else {
                    toast(L.s("Не нашёл курсор на снимках — откалибруйте касаниями", "Couldn't see the pointer — calibrate by tapping"));
                    startCalibration();
                }
            });
        }).start();
    }

    /** Taps on the TV picture: calibration, or point (single tap) / point + click (double tap). */
    private void attachPreviewGestures() {
        final android.view.GestureDetector gd = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (calStep >= 0) {
                    float[] t = preview.toTv(e.getX(), e.getY());
                    if (t != null) { haptic(preview); calibrationTap(t); }
                }
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (calStep >= 0) return true;
                float[] t = preview.toTv(e.getX(), e.getY());
                if (t == null) return true;
                haptic(preview);
                aimAt(t[0], t[1], false);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (calStep >= 0) return true;
                float[] t = preview.toTv(e.getX(), e.getY());
                if (t == null) return true;
                haptic(preview);
                aimAt(t[0], t[1], true);
                return true;
            }
        });
        preview.setOnTouchListener((v, e) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            gd.onTouchEvent(e);
            return true;
        });
    }

    /** Converts finger movement into pointer moves / clicks / scrolls, sent ~60 times a second. */
    private class PadTouch implements View.OnTouchListener {
        float lastX, lastY, travel;
        float twoX, twoY;   // two-finger swipe accumulated (dp)
        long downAt;
        boolean twoFinger, active;
        float accX, accY, accScroll;
        final Runnable flush = new Runnable() {
            @Override public void run() {
                int ix = (int) accX, iy = (int) accY, is = (int) accScroll;
                if (ix != 0 || iy != 0) {
                    lastPointerAt = SystemClock.uptimeMillis();
                    tv.move(ix, iy);
                    accX -= ix; accY -= iy;
                    curX = Math.max(0, Math.min(TvPreview.TV_W, curX + ix * kx()));
                    curY = Math.max(0, Math.min(TvPreview.TV_H, curY + iy * ky()));
                    if (preview != null) preview.setCursor(curX, curY, true);
                }
                if (is != 0) { tv.scroll(0, is); accScroll -= is; }
                if (active) ui.postDelayed(this, 16);
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            // Never let a parent take the gesture away mid-swipe.
            v.getParent().requestDisallowInterceptTouchEvent(true);
            lastTouchAt = SystemClock.uptimeMillis();
            float density = getResources().getDisplayMetrics().density;
            float sens = prefs.getFloat("sens", 1.6f);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX(); lastY = e.getY();
                    travel = 0; twoFinger = false; active = true;
                    downAt = SystemClock.uptimeMillis();
                    accX = accY = accScroll = 0;
                    showDot(e.getX(), e.getY(), true);
                    ui.removeCallbacks(flush);
                    ui.post(flush);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    twoFinger = true;
                    lastX = avgX(e);
                    lastY = avgY(e);
                    twoX = twoY = 0;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (twoFinger && e.getPointerCount() >= 2) {
                        // Two fingers = arrow keys (YouTube & most TV apps scroll with arrows, not with the pointer).
                        float x = avgX(e), y = avgY(e);
                        twoX += (x - lastX) / density;
                        twoY += (y - lastY) / density;
                        lastX = x;
                        lastY = y;
                        travel += 1;
                        final float STEP = 45f;   // dp of swipe per key press
                        while (Math.abs(twoX) >= STEP || Math.abs(twoY) >= STEP) {
                            String key;
                            if (Math.abs(twoY) >= Math.abs(twoX)) {
                                key = twoY < 0 ? "DOWN" : "UP";        // swipe up = show what's below
                                twoY -= Math.signum(twoY) * STEP;
                            } else {
                                key = twoX < 0 ? "RIGHT" : "LEFT";     // swipe left = show what's to the right
                                twoX -= Math.signum(twoX) * STEP;
                            }
                            arrowNow(key);
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            flash(arrowLabel(key));
                        }
                    } else if (!twoFinger) {
                        float dx = (e.getX() - lastX) / density, dy = (e.getY() - lastY) / density;
                        lastX = e.getX(); lastY = e.getY();
                        float dist = (float) Math.hypot(dx, dy);
                        travel += dist;
                        float accel = 1f + Math.min(dist / 12f, 2.5f);   // faster swipe = bigger jump
                        accX += dx * sens * accel;
                        accY += dy * sens * accel;
                        showDot(e.getX(), e.getY(), true);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    active = false;
                    flush.run();
                    showDot(0, 0, false);
                    long dur = SystemClock.uptimeMillis() - downAt;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && !twoFinger && travel < 8 && dur < 300) {
                        haptic(v);
                        tv.click();
                        flash(L.s("Клик", "Click"));
                    }
                    return true;
                default:
                    return true;
            }
        }

        private float avgX(MotionEvent e) {
            float s = 0;
            for (int k = 0; k < e.getPointerCount(); k++) s += e.getX(k);
            return s / e.getPointerCount();
        }

        private float avgY(MotionEvent e) {
            float s = 0;
            for (int k = 0; k < e.getPointerCount(); k++) s += e.getY(k);
            return s / e.getPointerCount();
        }
    }

    private static String arrowLabel(String key) {
        switch (key) {
            case "UP": return "▲ " + L.s("Вверх", "Up");
            case "DOWN": return "▼ " + L.s("Вниз", "Down");
            case "LEFT": return "◀ " + L.s("Влево", "Left");
            default: return "▶ " + L.s("Вправо", "Right");
        }
    }

    private void showDot(float x, float y, boolean on) {
        if (padDot == null) return;
        padDot.setAlpha(on ? 0.6f : 0f);
        if (on) {
            padDot.setTranslationX(x - dp(14));
            padDot.setTranslationY(y - dp(14));
        }
    }

    private void setMode(String mode) {
        boolean mouse = "mouse".equals(mode);
        prefs.edit().putString("mode", mouse ? "mouse" : "buttons").apply();
        dpad.setVisibility(mouse ? View.GONE : View.VISIBLE);
        pad.setVisibility(mouse ? View.VISIBLE : View.GONE);
        styleSeg(segButtons, !mouse);
        styleSeg(segMouse, mouse);
        applyLayout();
        if (mouse && !ip().isEmpty()) {
            tv.prepareInput();
            tv.resetScreenshot();
            if (previewOn()) syncCursor();
        }
    }

    private void setLang(String l) {
        prefs.edit().putString("lang", l).apply();
        boolean ru = l.startsWith("ru");
        styleSeg(langRu, ru);
        styleSeg(langEn, !ru);
    }

    private void styleSeg(TextView t, boolean on) {
        t.setBackground(on ? round(C_TEXT, 16) : null);
        t.setTextColor(on ? C_BG : C_LABEL);
    }

    // ---------------- widgets ----------------

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /**
     * A key: coloured shape with an icon (or text) exactly centred.
     * radiusDp = -1 → circle; bg = 0 → transparent.
     */
    private FrameLayout key(int iconRes, String label, int bg, int fg, int radiusDp, int iconDp, String desc,
                            View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable shape = radiusDp < 0 ? oval(bg == 0 ? 0x00000000 : bg) : round(bg, radiusDp);
        f.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, radiusDp < 0 ? oval(0xFFFFFFFF) : round(0xFFFFFFFF, radiusDp)));
        if (iconRes != 0) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(iconRes);
            iv.setColorFilter(fg, PorterDuff.Mode.SRC_IN);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            f.addView(iv, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        } else {
            TextView t = text(label, 15, fg, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            f.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        f.setContentDescription(desc != null ? desc : label);
        f.setClickable(true);
        f.setFocusable(true);
        final String fl = desc != null ? desc : label;
        final boolean doFlash = bg != 0;
        f.setOnClickListener(v -> { haptic(v); if (doFlash) flash(fl); l.onClick(v); });
        return f;
    }

    private FrameLayout appKey(String label, boolean app, View.OnClickListener l) {
        FrameLayout f = key(0, label, app ? C_APP_BG : C_KEY, app ? C_APP_TEXT : C_TEXT, 14, 0, label, l);
        if (app) {
            GradientDrawable g = round(C_APP_BG, 14);
            g.setStroke(dp(1), C_APP_EDGE);
            f.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, round(0xFFFFFFFF, 14)));
        }
        return f;
    }

    private TextView segItem(String label, View.OnClickListener l) {
        TextView t = text(label, 14, C_LABEL, true);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> { haptic(v); l.onClick(v); });
        return t;
    }

    /** Vertical rocker (VOL / CH). Volume repeats while held. */
    private View rocker(String label, int topRes, int botRes, String topDesc, String botDesc,
                        Runnable top, Runnable bottom, boolean repeat) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.setPadding(dp(4), dp(4), dp(4), dp(4));
        r.setBackground(round(C_KEY, 36));
        View t = key(topRes, null, C_KEY, C_TEXT, 32, 28, topDesc, v -> top.run());
        View b = key(botRes, null, C_KEY, C_TEXT, 32, 28, botDesc, v -> bottom.run());
        if (repeat) { autoRepeat(t, top); autoRepeat(b, bottom); }
        r.addView(t, new LinearLayout.LayoutParams(-1, dp(62)));
        TextView l = text(label, 12, C_LABEL, true);
        l.setGravity(Gravity.CENTER);
        l.setLetterSpacing(0.1f);
        r.addView(l, margins(new LinearLayout.LayoutParams(-1, -2), 0, 4, 0, 4));
        r.addView(b, new LinearLayout.LayoutParams(-1, dp(62)));
        return r;
    }

    private long lastPointerAt;
    private static final int SCROLL_STEP = 6;

    /**
     * Arrow that acts the moment it is touched and keeps going while held.
     * When the TV pointer was just used, LG spends the first arrow press only on leaving pointer mode,
     * so in that case one extra press is sent first.
     */
    private volatile String currentApp = "";

    private void arrowNow(String k) {
        if (currentApp.contains("browser")) {
            // In the web browser arrows only walk link to link; scroll the page itself instead.
            switch (k) {
                case "UP": tv.scroll(0, SCROLL_STEP); return;      // LG's scroll sign is opposite to the screen
                case "DOWN": tv.scroll(0, -SCROLL_STEP); return;
                case "LEFT": tv.scroll(-SCROLL_STEP, 0); return;
                default: tv.scroll(SCROLL_STEP, 0); return;
            }
        }
        if (SystemClock.uptimeMillis() - lastPointerAt < 15000) {
            tv.button(k);
            lastPointerAt = 0;
        }
        tv.button(k);
    }

    private void holdArrow(View v, String k) {
        final Runnable[] rep = new Runnable[1];
        rep[0] = () -> { if (currentApp.contains("browser")) arrowNow(k); else tv.button(k); ui.postDelayed(rep[0], 110); };
        v.setOnTouchListener((x, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x.getParent().requestDisallowInterceptTouchEvent(true);
                    x.setPressed(true);
                    haptic(x);
                    arrowNow(k);
                    flash(arrowLabel(k));
                    ui.postDelayed(rep[0], 300);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    x.setPressed(false);
                    ui.removeCallbacks(rep[0]);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void autoRepeat(View v, Runnable action) {
        final Runnable[] rep = new Runnable[1];
        rep[0] = () -> { action.run(); ui.postDelayed(rep[0], 150); };
        v.setOnLongClickListener(x -> { haptic(x); action.run(); ui.postDelayed(rep[0], 150); return true; });
        v.setOnTouchListener((x, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) ui.removeCallbacks(rep[0]);
            return false;
        });
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams size(int wDp, int hDp) { return new LinearLayout.LayoutParams(dp(wDp), dp(hDp)); }

    /** Equal-width cell filling its row's height, with a left gap. */
    private LinearLayout.LayoutParams weightLp(int leftGapDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(leftGapDp), 0, 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, int l, int t, int r, int b) {
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void haptic(View v) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // =====================================================================
    // Actions
    // =====================================================================

    private boolean playing = true;

    private void playPause() {
        tv.button(playing ? "PAUSE" : "PLAY");
        playing = !playing;
    }

    private void powerToggle() {
        if (connected) tv.powerOff();
        else powerOn();
    }

    private void showInputs() {
        String[] names = {"HDMI 1", "HDMI 2", "HDMI 3", L.s("Эфирное ТВ", "Live TV")};
        String[] ids = {"com.webos.app.hdmi1", "com.webos.app.hdmi2", "com.webos.app.hdmi3", "com.webos.app.livetv"};
        new AlertDialog.Builder(this).setTitle(L.s("Источник", "Input")).setItems(names, (d, w) -> tv.launch(ids[w])).show();
    }

    /** Open the TV's own browser, at an address or a Google search typed on the phone. */
    private void showBrowser() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText field = new EditText(this);
        field.setHint(L.s("адрес сайта или что искать", "web address or search"));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(prefs.getString("lastUrl", ""));
        box.addView(field);
        TextView quick = new TextView(this);
        quick.setText(L.s("Пусто → просто открыть браузер. Вход в Google — через google.com в самом браузере.",
                "Empty → just open the browser. Sign in to Google at google.com inside the browser."));
        quick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(quick);
        new AlertDialog.Builder(this)
                .setTitle(L.s("Браузер на телевизоре", "Browser on the TV"))
                .setView(box)
                .setPositiveButton(L.s("Открыть", "Open"), (d, w) -> {
                    String t = field.getText().toString().trim();
                    prefs.edit().putString("lastUrl", t).apply();
                    tv.openBrowser(toUrl(t));
                    flash("🌐 " + (t.isEmpty() ? L.s("Браузер", "Browser") : t));
                })
                .setNeutralButton(L.s("📱 Chrome с телефона", "📱 Chrome from phone"), (d, w) -> mirrorPhone())
                .setNegativeButton(L.s("Отмена", "Cancel"), null)
                .show();
        field.requestFocus();
    }

    /**
     * Chrome can't run on webOS, so show the phone's Chrome on the TV: open the phone's screen-mirroring
     * (Smart View / Cast) panel, the user picks the LG TV, then opens Chrome on the phone.
     */
    private void mirrorPhone() {
        String[] actions = {"android.settings.CAST_SETTINGS", "android.settings.WIFI_DISPLAY_SETTINGS"};
        for (String a : actions) {
            try {
                startActivity(new Intent(a));
                new AlertDialog.Builder(this)
                        .setTitle(L.s("Chrome на телевизоре", "Chrome on the TV"))
                        .setMessage(L.s("1) В открывшемся списке выберите телевизор LG (на ТВ может появиться запрос — нажмите «Принять»).\n"
                                        + "2) Вернитесь и нажмите «Открыть Chrome» — всё, что на телефоне, будет на экране ТВ.\n"
                                        + "3) Остановить: потяните шторку уведомлений на телефоне → Smart View → Отключить.",
                                "1) In the list that opened, pick the LG TV (the TV may ask — press Accept).\n"
                                        + "2) Come back and tap “Open Chrome” — the phone screen shows on the TV.\n"
                                        + "3) To stop: pull down notifications → Smart View → Disconnect."))
                        .setPositiveButton(L.s("Открыть Chrome", "Open Chrome"), (d, w) -> openPhoneChrome())
                        .setNegativeButton(L.s("Закрыть", "Close"), null)
                        .show();
                return;
            } catch (Exception ignored) { }
        }
        toast(L.s("Откройте шторку уведомлений → Smart View → выберите телевизор LG", "Pull down notifications → Smart View → pick the LG TV"));
    }

    private void openPhoneChrome() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.android.chrome");
            if (i == null) i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com"));
            startActivity(i);
        } catch (Exception e) {
            toast(L.s("Chrome не найден на телефоне", "Chrome not found on this phone"));
        }
    }

    private static String toUrl(String t) {
        if (t == null || t.isEmpty()) return "";
        if (t.matches("(?i)^https?://.*")) return t;
        if (!t.contains(" ") && t.matches(".*\\.[a-zA-Z]{2,}(/.*)?$")) return "https://" + t;
        try {
            return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8");
        } catch (Exception e) {
            return "https://www.google.com";
        }
    }

    private void showKeypad() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(4));
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"—", "0", "OK"}};
        for (String[] r : rows) {
            LinearLayout line = row();
            for (String d : r) {
                final String key = d.equals("—") ? "DASH" : d.equals("OK") ? "ENTER" : d;
                line.addView(appKey(d, false, v -> tv.button(key)), margins(new LinearLayout.LayoutParams(0, dp(56), 1f), 4, 0, 4, 0));
            }
            box.addView(line, margins(new LinearLayout.LayoutParams(-1, -2), 0, 4, 0, 4));
        }
        new AlertDialog.Builder(this).setTitle(L.s("Номер канала", "Channel number")).setView(box).setPositiveButton(L.s("Закрыть", "Close"), null).show();
    }

    private AlertDialog kbdDialog;

    private void showKeyboard() { showKeyboard(null); }

    /** Phone keyboard for the TV. When a TV text field is focused, every letter appears on the TV as you type. */
    private void showKeyboard(String prefill) {
        if (kbdDialog != null && kbdDialog.isShowing()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final TextView info = new TextView(this);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(info);
        final EditText field = new EditText(this);
        field.setHint(L.s("Текст…", "Text…"));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND);
        box.addView(field);
        final Runnable[] push = new Runnable[1];
        push[0] = () -> { if (tv.isTvKeyboardOpen()) tv.setFieldText(field.getText().toString()); };
        Runnable status = () -> info.setText(tv.isTvKeyboardOpen()
                ? L.s("✓ Поле на ТВ открыто — текст появляется там сразу", "✓ TV field is open — text appears there live")
                : L.s("Выберите строку поиска на ТВ (мышью или стрелками), потом пишите здесь", "Select the search box on the TV, then type here"));
        status.run();
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a1, int b1, int d) { }
            public void onTextChanged(CharSequence c, int a1, int b1, int d) { }
            public void afterTextChanged(android.text.Editable e) { ui.removeCallbacks(push[0]); ui.postDelayed(push[0], 150); }
        });
        LinearLayout btns = row();
        Button yt = new Button(this);
        yt.setText(L.s("В YouTube", "On YouTube"));
        yt.setAllCaps(false);
        yt.setOnClickListener(v -> { String t = field.getText().toString().trim(); if (!t.isEmpty()) { tv.youtubeSearch(t); kbdDialog.dismiss(); } });
        Button send = new Button(this);
        send.setText(L.s("Ввод ↵", "Enter ↵"));
        send.setAllCaps(false);
        send.setOnClickListener(v -> {
            String t = field.getText().toString();
            if (tv.isTvKeyboardOpen()) { ui.removeCallbacks(push[0]); tv.setFieldText(t); tv.enterKey(); kbdDialog.dismiss(); }
            else if (!t.trim().isEmpty()) tv.typeText(t, true);
        });
        btns.addView(yt, new LinearLayout.LayoutParams(0, -2, 1f));
        btns.addView(send, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(btns);
        field.setOnEditorActionListener((v, a1, ev) -> { send.performClick(); return true; });
        kbdDialog = new AlertDialog.Builder(this).setTitle(L.s("Клавиатура", "Keyboard")).setView(box)
                .setPositiveButton(L.s("Закрыть", "Close"), null).create();
        kbdDialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        kbdStatus = status;
        kbdDialog.setOnDismissListener(d -> kbdStatus = null);
        kbdDialog.show();
        if (prefill != null) { field.setText(prefill); field.setSelection(prefill.length()); }
        field.requestFocus();
    }

    private Runnable kbdStatus;

    @Override
    public void onTvKeyboard(boolean open) {
        ui.post(() -> {
            if (kbdStatus != null) kbdStatus.run();
            if (open && (kbdDialog == null || !kbdDialog.isShowing())) showKeyboard(null);
        });
    }

    // ---------------- voice ----------------

    private void voice(boolean toYoutube) {
        voiceToYoutube = toYoutube;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { voiceIntent(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startListening();
            else voiceIntent();
        }
    }

    private Intent speechIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang());
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang());
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, lang().startsWith("ru") ? L.s("Скажите, что искать", "Say what to search") : "Say what to search");
        return i;
    }

    private void startListening() {
        try {
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { statusLine.setText(L.s("🎤 Говорите…", "🎤 Speak…")); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { statusLine.setText(L.s("🎤 Распознаю…", "🎤 Recognizing…")); }
                @Override public void onPartialResults(Bundle b) { }
                @Override public void onEvent(int t, Bundle b) { }
                @Override public void onError(int code) {
                    log(L.s("голос: ошибка ", "voice: error ") + code);
                    restoreStatus();
                    if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        toast(L.s("Не расслышал, попробуйте ещё раз", "Didn't catch that, try again"));
                    } else {
                        toast(L.s("Голос не сработал (код ", "Voice failed (code ") + code + L.s("), открываю голосовой ввод Google", "), opening Google voice input"));
                        voiceIntent();
                    }
                }
                @Override public void onResults(Bundle b) {
                    restoreStatus();
                    ArrayList<String> res = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (res != null && !res.isEmpty()) handleVoiceText(res.get(0));
                    else toast(L.s("Не расслышал, попробуйте ещё раз", "Didn't catch that, try again"));
                }
            });
            recognizer.startListening(speechIntent());
        } catch (Exception e) {
            log(L.s("голос: ", "voice: ") + e);
            voiceIntent();
        }
    }

    private void voiceIntent() {
        try {
            startActivityForResult(speechIntent(), REQ_VOICE_INTENT);
        } catch (ActivityNotFoundException e) {
            toast(L.s("На телефоне нет голосового ввода — установите приложение Google", "No voice input on this phone — install the Google app"));
        }
    }

    private void handleVoiceText(String t) {
        log(L.s("голос: «", "voice: “") + t + "»" + (voiceToYoutube ? " → YouTube" : L.s(" → поле поиска", " → search field")));
        flash("🎤 " + t);
        if (voiceToYoutube) tv.youtubeSearch(t);
        else if (tv.isTvKeyboardOpen()) tv.typeText(t, true);
        else {
            // No text field open on the TV: keep the words and let the user pick where they go.
            showKeyboard(t);
            toast(L.s("Выберите строку поиска на ТВ — текст уже готов, нажмите «Ввод»",
                    "Select the search box on the TV — the text is ready, tap Enter"));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE_INTENT && resultCode == RESULT_OK && data != null) {
            ArrayList<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (res != null && !res.isEmpty()) handleVoiceText(res.get(0));
        }
    }

    // ---------------- power ----------------

    private void powerOn() {
        String mac = prefs.getString("mac", "");
        if (mac.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(L.s("Включение с телефона", "Turn on from phone"))
                    .setMessage(L.s("Телефон ещё не знает MAC-адрес телевизора.\n\n", "The phone doesn't know the TV's MAC address yet.\n\n")
                            + L.s("1) Включите ТВ обычным пультом и откройте это приложение один раз — MAC запомнится сам.\n", "1) Turn the TV on with the normal remote and open this app once — the MAC is saved automatically.\n")
                            + L.s("2) На ТВ включите: Настройки → Общие → Устройства → TV On With Mobile → Turn on via Wi-Fi.\n\n", "2) On the TV enable: Settings → General → Devices → TV On With Mobile → Turn on via Wi-Fi.\n\n")
                            + L.s("Или введите MAC вручную в настройках пульта.", "Or enter the MAC manually in remote settings."))
                    .setPositiveButton(L.s("Понятно", "OK"), null)
                    .show();
            return;
        }
        statusLine.setText(L.s("Включаю телевизор…", "Turning the TV on…"));
        final List<String> bc = broadcasts();
        new Thread(() -> {
            try {
                Net.wake(mac, bc);
                ui.post(() -> toast(L.s("Сигнал включения отправлен", "Power-on signal sent")));
                for (int k = 0; k < 6 && !tv.isConnected(); k++) {
                    Thread.sleep(2500);
                    tv.connect();
                }
            } catch (Exception e) {
                ui.post(() -> toast(L.s("Не удалось: ", "Failed: ") + e.getMessage()));
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private List<String> broadcasts() {
        List<String> out = new ArrayList<>();
        out.add("255.255.255.255");
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm == null ? null : wm.getDhcpInfo();
            if (d != null && d.ipAddress != 0 && d.netmask != 0) {
                int b = (d.ipAddress & d.netmask) | ~d.netmask;
                out.add((b & 0xff) + "." + ((b >> 8) & 0xff) + "." + ((b >> 16) & 0xff) + "." + ((b >> 24) & 0xff));
            }
        } catch (Exception ignored) { }
        String ip = ip();
        if (!ip.isEmpty() && ip.lastIndexOf('.') > 0) out.add(ip.substring(0, ip.lastIndexOf('.')) + ".255");
        return out;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { tv.volumeUp(); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { tv.volumeDown(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------- settings & discovery ----------------

    private void firstRunDiscover() {
        statusLine.setText(L.s("Ищу телевизор в Wi-Fi…", "Looking for the TV on Wi-Fi…"));
        discover(ips -> {
            if (ips.size() == 1) {
                saveIp(ips.get(0));
                toast(L.s("Нашёл телевизор: ", "Found TV: ") + ips.get(0));
            } else if (ips.isEmpty()) {
                statusLine.setText(L.s("Телевизор не найден — включите его и нажмите ⚙", "TV not found — turn it on and tap ⚙"));
            } else {
                pickIp(ips);
            }
        });
    }

    private interface IpsCallback { void done(List<String> ips); }

    private void discover(IpsCallback cb) {
        new Thread(() -> {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = null;
            List<String> ips = new ArrayList<>();
            try {
                if (wm != null) { lock = wm.createMulticastLock("tvremote"); lock.setReferenceCounted(false); lock.acquire(); }
                ips = Net.discover(3500);
            } catch (Exception ignored) {
            } finally {
                if (lock != null) lock.release();
            }
            final List<String> r = ips;
            ui.post(() -> cb.done(r));
        }).start();
    }

    private void pickIp(List<String> ips) {
        String[] arr = ips.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(L.s("Найдено несколько ТВ", "Several TVs found")).setItems(arr, (d, w) -> saveIp(arr[w])).show();
    }

    private void saveIp(String ip) {
        boolean changed = !ip.equals(ip());
        SharedPreferences.Editor e = prefs.edit().putString("ip", ip);
        if (changed) e.remove("key");
        e.apply();
        tvName.setText("LG · " + ip);
        tv.setTarget(ip, changed ? "" : prefs.getString("key", ""));
        tv.connect();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText ipField = new EditText(this);
        ipField.setHint(L.s("IP телевизора, напр. 192.168.1.50", "TV IP, e.g. 192.168.1.50"));
        ipField.setInputType(InputType.TYPE_CLASS_TEXT);
        ipField.setText(ip());
        final EditText macField = new EditText(this);
        macField.setHint(L.s("MAC (для включения), напр. A1:B2:C3:D4:E5:F6", "MAC (for power-on), e.g. A1:B2:C3:D4:E5:F6"));
        macField.setText(prefs.getString("mac", ""));
        box.addView(ipField);
        box.addView(macField);

        TextView sensT = new TextView(this);
        sensT.setText(L.s("Скорость мыши:", "Mouse speed:"));
        sensT.setPadding(0, dp(12), 0, dp(4));
        box.addView(sensT);
        LinearLayout sensRow = row();
        String[] sl = {L.s("Медленно", "Slow"), L.s("Средне", "Medium"), L.s("Быстро", "Fast")};
        float[] sv = {1.0f, 1.6f, 2.5f};
        final Button[] sb = new Button[3];
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            sb[k] = new Button(this);
            sb[k].setAllCaps(false);
            sb[k].setText(sl[k]);
            sb[k].setOnClickListener(v -> {
                prefs.edit().putFloat("sens", sv[kk]).apply();
                for (int j = 0; j < 3; j++) sb[j].setText((j == kk ? "✓ " : "") + sl[j]);
            });
            sensRow.addView(sb[k], new LinearLayout.LayoutParams(0, -2, 1f));
        }
        float cur = prefs.getFloat("sens", 1.6f);
        for (int j = 0; j < 3; j++) if (Math.abs(sv[j] - cur) < 0.01f) sb[j].setText("✓ " + sl[j]);
        box.addView(sensRow);

        final Button prevBtn = new Button(this);
        prevBtn.setAllCaps(false);
        Runnable prevLabel = () -> prevBtn.setText(L.s("Экран ТВ над тачпадом: ", "TV screen above touchpad: ")
                + (previewOn() ? L.s("показывать", "show") : L.s("скрыть", "hide")));
        prevLabel.run();
        prevBtn.setOnClickListener(v -> {
            prefs.edit().putBoolean("preview", !previewOn()).apply();
            prevLabel.run();
            applyPreviewSetting();
        });
        box.addView(prevBtn, margins(new LinearLayout.LayoutParams(-1, -2), 0, 10, 0, 0));

        final Button uiLang = new Button(this);
        uiLang.setAllCaps(false);
        uiLang.setText(L.s("Язык приложения: Русский → English", "App language: English → Русский"));
        uiLang.setOnClickListener(v -> {
            prefs.edit().putString("ui", L.en ? "ru" : "en").apply();
            recreate();
        });
        box.addView(uiLang, margins(new LinearLayout.LayoutParams(-1, -2), 0, 10, 0, 0));

        TextView info = new TextView(this);
        info.setText(L.s("IP на ТВ: Настройки → Общие → Сеть → Wi-Fi → Дополнительно. MAC запоминается сам. Версия ", "TV IP: Settings → General → Network → Wi-Fi → Advanced. MAC is saved automatically. Version ") + BuildInfo.VERSION);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setPadding(0, dp(8), 0, 0);
        box.addView(info);

        new AlertDialog.Builder(this)
                .setTitle(L.s("Настройки пульта", "Remote settings"))
                .setView(box)
                .setPositiveButton(L.s("Сохранить", "Save"), (d, w) -> {
                    String mac = macField.getText().toString().trim();
                    if (!mac.isEmpty()) {
                        try { Net.parseMac(mac); prefs.edit().putString("mac", mac.toUpperCase()).apply(); }
                        catch (IllegalArgumentException ex) { toast(ex.getMessage()); }
                    }
                    String newIp = ipField.getText().toString().trim();
                    if (!newIp.isEmpty()) saveIp(newIp);
                })
                .setNeutralButton(L.s("Найти ТВ", "Find TV"), (d, w) -> firstRunDiscover())
                .setNegativeButton(L.s("Сбросить пару", "Reset pairing"), (d, w) -> {
                    prefs.edit().remove("key").apply();
                    tv.setTarget("", "");
                    if (!ip().isEmpty()) { tv.setTarget(ip(), ""); tv.connect(); }
                    toast(L.s("Подтвердите подключение на телевизоре", "Accept the connection on the TV"));
                })
                .show();
    }

    // ---------------- log & status ----------------

    private void log(String line) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        logLines.addFirst(ts + " " + line);
        while (logLines.size() > 40) logLines.removeLast();
        StringBuilder b = new StringBuilder();
        for (String l : logLines) b.append(l).append('\n');
        logView.setText(b.toString().trim());
    }

    private void restoreStatus() {
        statusLine.setText(connected ? L.s("Подключено", "Connected") : L.s("Нет связи", "Not connected"));
        statusLine.setTextColor(connected ? 0xFF7EE0A0 : C_DIM);
    }

    // ---------------- TvClient.Listener (background threads) ----------------

    @Override
    public void onStatus(String text, boolean isConnected) {
        ui.post(() -> {
            connected = isConnected;
            statusLine.setText(isConnected ? L.s("Подключено", "Connected") + infoText : text);
            statusLine.setTextColor(isConnected ? 0xFF7EE0A0 : C_DIM);
            log(text);
        });
    }

    @Override
    public void onClientKey(String key) { prefs.edit().putString("key", key).apply(); }

    @Override
    public void onMac(String mac) { prefs.edit().putString("mac", mac).apply(); }

    @Override
    public void onError(String text) {
        ui.post(() -> {
            log(L.s("ошибка: ", "error: ") + text);
            if (!onWifi()) {
                statusLine.setText(L.s("Телефон не в Wi-Fi", "Phone is not on Wi-Fi"));
                toast(L.s("Телефон не подключён к домашнему Wi-Fi — пульт работает только через Wi-Fi, где телевизор",
                        "The phone is not on your home Wi-Fi — the remote only works on the TV's Wi-Fi"));
            } else if (text != null && (text.contains("failed to connect") || text.contains("ECONNREFUSED") || text.contains("timed out"))) {
                toast(L.s("Телевизор не отвечает — он включён и в том же Wi-Fi?", "TV not answering — is it on and on the same Wi-Fi?"));
            } else {
                toast(text);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private boolean onWifi() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public void onLog(String text) { ui.post(() -> log(text)); }
}
