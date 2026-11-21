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
        statusLine = text("Нет связи", 12, C_DIM, false);
        tvName = text(ip().isEmpty() ? "Телевизор не настроен" : "LG · " + ip(), 16, C_TEXT, true);
        tvName.setSingleLine(true);
        names.addView(statusLine);
        names.addView(tvName);
        header.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(key(R.drawable.ic_list, null, 0, C_DIM, -1, 22, "Журнал",
                v -> logView.setVisibility(logView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), size(44, 44));
        header.addView(key(R.drawable.ic_cog, null, 0, C_DIM, -1, 22, "Настройки пульта", v -> showSettings()), size(44, 44));
        View power = key(R.drawable.ic_power, null, C_POWER, Color.WHITE, -1, 24, "Питание", v -> powerToggle());
        power.setOnLongClickListener(v -> { haptic(v); powerOn(); return true; });
        header.addView(power, margins(size(52, 52), 8, 0, 0, 0));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        // --- back · home · mute · menu · keyboard
        LinearLayout nav = row();
        nav.addView(key(R.drawable.ic_back, null, C_KEY, C_TEXT, 26, 22, "Назад", v -> tv.button("BACK")), weightLp(0));
        nav.addView(key(R.drawable.ic_home, null, C_KEY, C_TEXT, 26, 22, "Домой", v -> tv.button("HOME")), weightLp(8));
        nav.addView(key(R.drawable.ic_mute, null, C_KEY, C_TEXT, 26, 22, "Без звука", v -> tv.toggleMute()), weightLp(8));
        nav.addView(key(R.drawable.ic_menu, null, C_KEY, C_TEXT, 26, 22, "Меню телевизора", v -> tv.button("MENU")), weightLp(8));
        nav.addView(key(R.drawable.ic_kbd, null, C_KEY, C_TEXT, 26, 22, "Клавиатура", v -> showKeyboard()), weightLp(8));
        root.addView(nav, margins(new LinearLayout.LayoutParams(-1, dp(52)), 0, 10, 0, 0));

        // --- segmented: Кнопки | Мышь
        LinearLayout seg = row();
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackground(round(C_KEY, 22));
        segButtons = segItem("Кнопки", v -> setMode("buttons"));
        segMouse = segItem("Мышь", v -> setMode("mouse"));
        seg.addView(segButtons, size(112, 36));
        seg.addView(segMouse, margins(size(112, 36), 4, 0, 0, 0));
        LinearLayout segRow = row();
        segRow.setGravity(Gravity.CENTER);
        segRow.addView(seg);
        root.addView(segRow, margins(new LinearLayout.LayoutParams(-1, -2), 0, 14, 0, 0));

        // --- center (fills the free space): d-pad or touchpad; the log overlays it
        FrameLayout center = new FrameLayout(this);
        DpadView dv = new DpadView(this, C_KEY, C_OK, C_TEXT);
        dv.setOnKey(name -> tv.button(name));
        dpad = dv;
        pad = buildPad();
        center.addView(dpad, new FrameLayout.LayoutParams(-1, -1));
        center.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        logView = text("Журнал пуст", 11, 0xFF9FD3A8, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackground(round(0xF00A0C0F, 16));
        logView.setPadding(dp(10), dp(8), dp(10), dp(8));
        logView.setVerticalScrollBarEnabled(true);
        logView.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        logView.setVisibility(View.GONE);
        center.addView(logView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(center, margins(new LinearLayout.LayoutParams(-1, 0, 1f), 0, 14, 0, 14));

        // --- VOL rocker · mic + language · CH rocker
        LinearLayout rockers = row();
        rockers.setGravity(Gravity.CENTER_VERTICAL);
        rockers.addView(rocker("VOL", R.drawable.ic_plus, R.drawable.ic_minus, "Громче", "Тише",
                () -> tv.volumeUp(), () -> tv.volumeDown(), true), new LinearLayout.LayoutParams(dp(72), -2));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        View mic = key(R.drawable.ic_mic, null, C_TEXT, C_BG, -1, 34, "Голосовой поиск", v -> voice(false));
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
        rockers.addView(rocker("CH", R.drawable.ic_up, R.drawable.ic_down, "Канал вверх", "Канал вниз",
                () -> tv.channelUp(), () -> tv.channelDown(), false), new LinearLayout.LayoutParams(dp(72), -2));
        root.addView(rockers, new LinearLayout.LayoutParams(-1, -2));
        setLang(lang());

        // --- playback
        LinearLayout play = row();
        play.addView(key(R.drawable.ic_rew, null, C_KEY, C_TEXT, 14, 22, "Перемотка назад", v -> tv.button("REWIND")), weightLp(0));
        play.addView(key(R.drawable.ic_playpause, null, C_KEY, C_TEXT, 14, 22, "Пауза / воспроизведение", v -> playPause()), weightLp(8));
        play.addView(key(R.drawable.ic_ff, null, C_KEY, C_TEXT, 14, 22, "Перемотка вперёд", v -> tv.button("FASTFORWARD")), weightLp(8));
        root.addView(play, margins(new LinearLayout.LayoutParams(-1, dp(46)), 0, 14, 0, 0));

        // --- apps
        LinearLayout apps = row();
        apps.addView(appKey("YouTube", true, v -> tv.launch("youtube.leanback.v4")), weightLp(0));
        apps.addView(appKey("Netflix", true, v -> tv.launch("netflix")), weightLp(8));
        apps.addView(appKey("HDMI", false, v -> showInputs()), weightLp(8));
        apps.addView(appKey("123", false, v -> showKeypad()), weightLp(8));
        root.addView(apps, margins(new LinearLayout.LayoutParams(-1, dp(46)), 0, 10, 0, 0));
        return root;
    }

    // ---------------- touchpad ----------------

    private View buildPad() {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = round(C_PAD, 28);
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
        hints.addView(cur, size(40, 40));
        TextView h1 = text("Водите пальцем — курсор на ТВ", 15, 0xFFC9CED6, true);
        TextView h2 = text("касание — клик · двумя пальцами — прокрутка", 12, C_DIM, false);
        h1.setGravity(Gravity.CENTER);
        h2.setGravity(Gravity.CENTER);
        hints.addView(h1, margins(new LinearLayout.LayoutParams(-2, -2), 0, 10, 0, 0));
        hints.addView(h2, margins(new LinearLayout.LayoutParams(-2, -2), 0, 4, 0, 0));
        f.addView(hints, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        f.setOnTouchListener(new PadTouch());
        return f;
    }

    /** Converts finger movement into pointer moves / clicks / scrolls, sent ~60 times a second. */
    private class PadTouch implements View.OnTouchListener {
        float lastX, lastY, travel;
        long downAt;
        boolean twoFinger, active;
        float accX, accY, accScroll;
        final Runnable flush = new Runnable() {
            @Override public void run() {
                int ix = (int) accX, iy = (int) accY, is = (int) accScroll;
                if (ix != 0 || iy != 0) { tv.move(ix, iy); accX -= ix; accY -= iy; }
                if (is != 0) { tv.scroll(0, is); accScroll -= is; }
                if (active) ui.postDelayed(this, 16);
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            // Never let a parent take the gesture away mid-swipe.
            v.getParent().requestDisallowInterceptTouchEvent(true);
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
                    lastY = avgY(e);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (twoFinger && e.getPointerCount() >= 2) {
                        float y = avgY(e);
                        float d = (y - lastY) / density;
                        lastY = y;
                        travel += Math.abs(d);
                        accScroll += -d * 1.2f;   // finger up = page scrolls down
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
                    }
                    return true;
                default:
                    return true;
            }
        }

        private float avgY(MotionEvent e) {
            float s = 0;
            for (int k = 0; k < e.getPointerCount(); k++) s += e.getY(k);
            return s / e.getPointerCount();
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
        if (mouse && !ip().isEmpty()) tv.prepareInput();
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
        f.setOnClickListener(v -> { haptic(v); l.onClick(v); });
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
        String[] names = {"HDMI 1", "HDMI 2", "HDMI 3", "Эфирное ТВ"};
        String[] ids = {"com.webos.app.hdmi1", "com.webos.app.hdmi2", "com.webos.app.hdmi3", "com.webos.app.livetv"};
        new AlertDialog.Builder(this).setTitle("Источник").setItems(names, (d, w) -> tv.launch(ids[w])).show();
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
        new AlertDialog.Builder(this).setTitle("Номер канала").setView(box).setPositiveButton("Закрыть", null).show();
    }

    private void showKeyboard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        TextView info = new TextView(this);
        info.setText("Откройте поиск на телевизоре, затем пишите здесь.");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(info);
        final EditText field = new EditText(this);
        field.setHint("Текст…");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND);
        box.addView(field);
        LinearLayout btns = row();
        Button del = new Button(this);
        del.setText("⌫");
        del.setOnClickListener(v -> tv.deleteChars(1));
        Button yt = new Button(this);
        yt.setText("В YouTube");
        yt.setAllCaps(false);
        yt.setOnClickListener(v -> { String t = field.getText().toString().trim(); if (!t.isEmpty()) { tv.youtubeSearch(t); field.setText(""); } });
        Button send = new Button(this);
        send.setText("Ввести");
        send.setAllCaps(false);
        send.setOnClickListener(v -> { String t = field.getText().toString(); if (!t.trim().isEmpty()) { tv.typeText(t, true); field.setText(""); } });
        btns.addView(del, new LinearLayout.LayoutParams(0, -2, 1f));
        btns.addView(yt, new LinearLayout.LayoutParams(0, -2, 1.4f));
        btns.addView(send, new LinearLayout.LayoutParams(0, -2, 1.4f));
        box.addView(btns);
        field.setOnEditorActionListener((v, a, ev) -> { send.performClick(); return true; });
        new AlertDialog.Builder(this).setTitle("Клавиатура").setView(box).setPositiveButton("Закрыть", null).show();
        field.requestFocus();
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
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, lang().startsWith("ru") ? "Скажите, что искать" : "Say what to search");
        return i;
    }

    private void startListening() {
        try {
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { statusLine.setText("🎤 Говорите…"); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { statusLine.setText("🎤 Распознаю…"); }
                @Override public void onPartialResults(Bundle b) { }
                @Override public void onEvent(int t, Bundle b) { }
                @Override public void onError(int code) {
                    log("голос: ошибка " + code);
                    restoreStatus();
                    if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        toast("Не расслышал, попробуйте ещё раз");
                    } else {
                        toast("Голос не сработал (код " + code + "), открываю голосовой ввод Google");
                        voiceIntent();
                    }
                }
                @Override public void onResults(Bundle b) {
                    restoreStatus();
                    ArrayList<String> res = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (res != null && !res.isEmpty()) handleVoiceText(res.get(0));
                    else toast("Не расслышал, попробуйте ещё раз");
                }
            });
            recognizer.startListening(speechIntent());
        } catch (Exception e) {
            log("голос: " + e);
            voiceIntent();
        }
    }

    private void voiceIntent() {
        try {
            startActivityForResult(speechIntent(), REQ_VOICE_INTENT);
        } catch (ActivityNotFoundException e) {
            toast("На телефоне нет голосового ввода — установите приложение Google");
        }
    }

    private void handleVoiceText(String t) {
        log("голос: «" + t + "»" + (voiceToYoutube ? " → YouTube" : " → поле поиска"));
        toast("Ищу: " + t);
        if (voiceToYoutube) tv.youtubeSearch(t);
        else tv.typeText(t, true);
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
                    .setTitle("Включение с телефона")
                    .setMessage("Телефон ещё не знает MAC-адрес телевизора.\n\n"
                            + "1) Включите ТВ обычным пультом и откройте это приложение один раз — MAC запомнится сам.\n"
                            + "2) На ТВ включите: Настройки → Общие → Устройства → TV On With Mobile → Turn on via Wi-Fi.\n\n"
                            + "Или введите MAC вручную в настройках пульта.")
                    .setPositiveButton("Понятно", null)
                    .show();
            return;
        }
        statusLine.setText("Включаю телевизор…");
        final List<String> bc = broadcasts();
        new Thread(() -> {
            try {
                Net.wake(mac, bc);
                ui.post(() -> toast("Сигнал включения отправлен"));
                for (int k = 0; k < 6 && !tv.isConnected(); k++) {
                    Thread.sleep(2500);
                    tv.connect();
                }
            } catch (Exception e) {
                ui.post(() -> toast("Не удалось: " + e.getMessage()));
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
        statusLine.setText("Ищу телевизор в Wi-Fi…");
        discover(ips -> {
            if (ips.size() == 1) {
                saveIp(ips.get(0));
                toast("Нашёл телевизор: " + ips.get(0));
            } else if (ips.isEmpty()) {
                statusLine.setText("Телевизор не найден — включите его и нажмите ⚙");
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
        new AlertDialog.Builder(this).setTitle("Найдено несколько ТВ").setItems(arr, (d, w) -> saveIp(arr[w])).show();
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
        ipField.setHint("IP телевизора, напр. 192.168.1.50");
        ipField.setInputType(InputType.TYPE_CLASS_TEXT);
        ipField.setText(ip());
        final EditText macField = new EditText(this);
        macField.setHint("MAC (для включения), напр. A1:B2:C3:D4:E5:F6");
        macField.setText(prefs.getString("mac", ""));
        box.addView(ipField);
        box.addView(macField);

        TextView sensT = new TextView(this);
        sensT.setText("Скорость мыши:");
        sensT.setPadding(0, dp(12), 0, dp(4));
        box.addView(sensT);
        LinearLayout sensRow = row();
        String[] sl = {"Медленно", "Средне", "Быстро"};
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

        TextView info = new TextView(this);
        info.setText("IP на ТВ: Настройки → Общие → Сеть → Wi-Fi → Дополнительно. MAC запоминается сам. Версия " + BuildInfo.VERSION);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setPadding(0, dp(8), 0, 0);
        box.addView(info);

        new AlertDialog.Builder(this)
                .setTitle("Настройки пульта")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String mac = macField.getText().toString().trim();
                    if (!mac.isEmpty()) {
                        try { Net.parseMac(mac); prefs.edit().putString("mac", mac.toUpperCase()).apply(); }
                        catch (IllegalArgumentException ex) { toast(ex.getMessage()); }
                    }
                    String newIp = ipField.getText().toString().trim();
                    if (!newIp.isEmpty()) saveIp(newIp);
                })
                .setNeutralButton("Найти ТВ", (d, w) -> firstRunDiscover())
                .setNegativeButton("Сбросить пару", (d, w) -> {
                    prefs.edit().remove("key").apply();
                    tv.setTarget("", "");
                    if (!ip().isEmpty()) { tv.setTarget(ip(), ""); tv.connect(); }
                    toast("Подтвердите подключение на телевизоре");
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
        statusLine.setText(connected ? "Подключено" : "Нет связи");
        statusLine.setTextColor(connected ? 0xFF7EE0A0 : C_DIM);
    }

    // ---------------- TvClient.Listener (background threads) ----------------

    @Override
    public void onStatus(String text, boolean isConnected) {
        ui.post(() -> {
            connected = isConnected;
            statusLine.setText(isConnected ? "Подключено" : text);
            statusLine.setTextColor(isConnected ? 0xFF7EE0A0 : C_DIM);
            log(text);
        });
    }

    @Override
    public void onClientKey(String key) { prefs.edit().putString("key", key).apply(); }

    @Override
    public void onMac(String mac) { prefs.edit().putString("mac", mac).apply(); }

    @Override
    public void onError(String text) { ui.post(() -> { log("ошибка: " + text); toast(text); }); }

    @Override
    public void onLog(String text) { ui.post(() -> log(text)); }
}
