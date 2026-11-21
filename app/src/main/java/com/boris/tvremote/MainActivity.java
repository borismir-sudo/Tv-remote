package com.boris.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements TvClient.Listener {

    private static final int REQ_VOICE_RU = 101;
    private static final int REQ_VOICE_EN = 102;

    private static final int C_BG = 0xFF111418;
    private static final int C_KEY = 0xFF262B33;
    private static final int C_NAV = 0xFF1F3B5C;
    private static final int C_OK = 0xFF2F6FB3;
    private static final int C_ON = 0xFF1E7A3C;
    private static final int C_OFF = 0xFFA12B2B;
    private static final int C_APP = 0xFF3A2F5C;

    private SharedPreferences prefs;
    private TvClient tv;
    private TextView status;
    private EditText textField;
    private LinearLayout keypad;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        tv = new TvClient(this);
        setContentView(buildUi());
        if (ip().isEmpty()) {
            ui.postDelayed(this::firstRunDiscover, 400);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ip().isEmpty()) {
            tv.setTarget(ip(), prefs.getString("key", ""));
            tv.connect();
        }
    }

    @Override
    protected void onDestroy() {
        tv.shutdown();
        super.onDestroy();
    }

    private String ip() { return prefs.getString("ip", ""); }

    // ---------------- UI ----------------

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        root.setPadding(p, dp(28), p, p);
        sv.addView(root);

        // Header: status + settings
        LinearLayout header = row();
        status = new TextView(this);
        status.setTextColor(0xFFB0B8C4);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setText(ip().isEmpty() ? "Телевизор не настроен" : "Телевизор: " + ip());
        header.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button gear = key("⚙", C_KEY, v -> showSettings());
        header.addView(gear, new LinearLayout.LayoutParams(dp(56), dp(48)));
        root.addView(header);

        // Power
        root.addView(row(
                key("⏻  ВКЛ", C_ON, v -> powerOn()),
                key("⏻  ВЫКЛ", C_OFF, v -> tv.powerOff())));

        // Volume / mute / channels
        LinearLayout vc = row();
        LinearLayout vol = col(
                key("VOL +", C_KEY, v -> tv.volumeUp()),
                key("🔇", C_KEY, v -> tv.toggleMute()),
                key("VOL −", C_KEY, v -> tv.volumeDown()));
        LinearLayout mid = col(
                key("🏠 Home", C_KEY, v -> tv.button("HOME")),
                key("123", C_KEY, v -> keypad.setVisibility(keypad.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)),
                key("⚙ Меню", C_KEY, v -> tv.button("MENU")));
        LinearLayout ch = col(
                key("CH +", C_KEY, v -> tv.channelUp()),
                key("📺 ТВ", C_KEY, v -> tv.launch("com.webos.app.livetv")),
                key("CH −", C_KEY, v -> tv.channelDown()));
        vc.addView(vol, weight());
        vc.addView(mid, weight());
        vc.addView(ch, weight());
        root.addView(vc);

        // Number keypad (hidden by default)
        keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setVisibility(View.GONE);
        String[][] digits = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"—", "0", "OK"}};
        for (String[] r : digits) {
            LinearLayout kr = row();
            for (String d : r) {
                final String name = d.equals("—") ? "DASH" : d.equals("OK") ? "ENTER" : d;
                kr.addView(key(d, C_KEY, v -> tv.button(name)), weight());
            }
            keypad.addView(kr);
        }
        root.addView(keypad);

        // D-pad
        root.addView(spacer(6));
        root.addView(row(blank(), key("▲", C_NAV, v -> tv.button("UP")), blank()));
        root.addView(row(
                key("◀", C_NAV, v -> tv.button("LEFT")),
                key("OK", C_OK, v -> tv.button("ENTER")),
                key("▶", C_NAV, v -> tv.button("RIGHT"))));
        root.addView(row(blank(), key("▼", C_NAV, v -> tv.button("DOWN")), blank()));
        root.addView(row(
                key("↩ Назад", C_KEY, v -> tv.button("BACK")),
                key("✖ Выход", C_KEY, v -> tv.button("EXIT"))));

        // Playback
        root.addView(row(
                key("⏪", C_KEY, v -> tv.button("REWIND")),
                key("▶", C_KEY, v -> tv.button("PLAY")),
                key("⏸", C_KEY, v -> tv.button("PAUSE")),
                key("⏩", C_KEY, v -> tv.button("FASTFORWARD"))));

        // Search / text
        root.addView(spacer(8));
        TextView hint = new TextView(this);
        hint.setText("Поиск: откройте поиск на ТВ, потом пишите или говорите здесь");
        hint.setTextColor(0xFF8A94A3);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(hint);
        textField = new EditText(this);
        textField.setTextColor(Color.WHITE);
        textField.setHintTextColor(0xFF6B7380);
        textField.setHint("Текст для поиска…");
        textField.setSingleLine(true);
        textField.setInputType(InputType.TYPE_CLASS_TEXT);
        textField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        textField.setOnEditorActionListener((v, actionId, ev) -> { sendText(); return true; });
        root.addView(textField);
        root.addView(row(
                key("🎤 RU", C_APP, v -> voice(REQ_VOICE_RU)),
                key("🎤 EN", C_APP, v -> voice(REQ_VOICE_EN)),
                key("⌫", C_KEY, v -> tv.deleteChars(1)),
                key("Ввод ➜", C_OK, v -> sendText())));

        // Apps
        root.addView(spacer(8));
        root.addView(row(
                key("YouTube", C_APP, v -> tv.launch("youtube.leanback.v4")),
                key("Netflix", C_APP, v -> tv.launch("netflix")),
                key("Prime", C_APP, v -> tv.launch("amazon"))));
        root.addView(row(
                key("HDMI 1", C_APP, v -> tv.launch("com.webos.app.hdmi1")),
                key("HDMI 2", C_APP, v -> tv.launch("com.webos.app.hdmi2")),
                key("HDMI 3", C_APP, v -> tv.launch("com.webos.app.hdmi3"))));

        TextView foot = new TextView(this);
        foot.setText("Кнопки громкости телефона тоже управляют громкостью ТВ");
        foot.setTextColor(0xFF5D6673);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(10), 0, dp(10));
        root.addView(foot);
        return sv;
    }

    private Button key(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setMinHeight(dp(58));
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            l.onClick(v);
        });
        return b;
    }

    private View blank() { return new View(this); }

    private LinearLayout row(View... children) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View c : children) r.addView(c, weight());
        return r;
    }

    private LinearLayout col(View... children) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        for (View v : children) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62));
            int m = dp(3);
            lp.setMargins(m, m, m, m);
            c.addView(v, lp);
        }
        return c;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1f);
        int m = dp(3);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------- actions ----------------

    private void sendText() {
        String t = textField.getText().toString();
        if (t.trim().isEmpty()) return;
        tv.typeText(t, true);
        textField.setText("");
    }

    private void voice(int req) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String lang = req == REQ_VOICE_RU ? "ru-RU" : "en-US";
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, req == REQ_VOICE_RU ? "Скажите, что искать" : "Say what to search");
        try {
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            toast("На телефоне нет голосового ввода Google");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQ_VOICE_RU || requestCode == REQ_VOICE_EN) && resultCode == RESULT_OK && data != null) {
            ArrayList<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (res != null && !res.isEmpty()) {
                String t = res.get(0);
                toast("Ищу: " + t);
                tv.typeText(t, true);
            }
        }
    }

    private void powerOn() {
        String mac = prefs.getString("mac", "");
        if (mac.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Включение с телефона")
                    .setMessage("Телефон ещё не знает MAC-адрес телевизора.\n\n"
                            + "1) Включите ТВ обычным пультом и откройте это приложение один раз — MAC запомнится сам.\n"
                            + "2) На ТВ включите: Настройки → Общие → Устройства → TV On With Mobile → Turn on via Wi-Fi.\n\n"
                            + "Или введите MAC вручную в ⚙.")
                    .setPositiveButton("Понятно", null)
                    .show();
            return;
        }
        status.setText("Включаю телевизор…");
        final List<String> bc = broadcasts();
        new Thread(() -> {
            try {
                Net.wake(mac, bc);
                ui.post(() -> toast("Сигнал включения отправлен"));
                // Give the TV time to boot its network stack, then connect.
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
        status.setText("Ищу телевизор в Wi-Fi…");
        discover(ips -> {
            if (ips.size() == 1) {
                saveIp(ips.get(0));
                toast("Нашёл телевизор: " + ips.get(0));
            } else if (ips.isEmpty()) {
                status.setText("Телевизор не найден — включите его и нажмите ⚙");
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
        new AlertDialog.Builder(this)
                .setTitle("Найдено несколько ТВ")
                .setItems(arr, (d, which) -> saveIp(arr[which]))
                .show();
    }

    private void saveIp(String ip) {
        boolean changed = !ip.equals(ip());
        SharedPreferences.Editor e = prefs.edit().putString("ip", ip);
        if (changed) e.remove("key");
        e.apply();
        status.setText("Телевизор: " + ip);
        tv.setTarget(ip, changed ? "" : prefs.getString("key", ""));
        tv.connect();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);
        final EditText ipField = new EditText(this);
        ipField.setHint("IP телевизора, напр. 192.168.1.50");
        ipField.setInputType(InputType.TYPE_CLASS_TEXT);
        ipField.setText(ip());
        final EditText macField = new EditText(this);
        macField.setHint("MAC (для включения), напр. A1:B2:C3:D4:E5:F6");
        macField.setText(prefs.getString("mac", ""));
        TextView info = new TextView(this);
        info.setText("IP на ТВ: Настройки → Общие → Сеть → Wi-Fi → Дополнительно.\nMAC запоминается сам после первого подключения.");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(ipField);
        box.addView(macField);
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

    // ---------------- TvClient.Listener (background threads) ----------------

    @Override
    public void onStatus(String text, boolean connected) {
        ui.post(() -> {
            status.setText((connected ? "🟢 " : "⚪ ") + text);
            status.setTextColor(connected ? 0xFF7EE0A0 : 0xFFB0B8C4);
        });
    }

    @Override
    public void onClientKey(String key) { prefs.edit().putString("key", key).apply(); }

    @Override
    public void onMac(String mac) { prefs.edit().putString("mac", mac).apply(); }

    @Override
    public void onError(String text) { ui.post(() -> toast(text)); }
}
