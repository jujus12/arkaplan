package com.arkaplan.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_SHIZUKU = 100;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private TextView shizukuStatus;
    private TextView log;
    private final TextView[] pickLabel = new TextView[2];
    private final EditText[] userEdit = new EditText[2];
    private final TextView[] slotStatus = new TextView[2];
    private final long[] lastFrames = new long[2];
    private final long[] lastTick = new long[2];

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (code, result) -> runOnUiThread(this::refreshShizuku);
    private final Shizuku.OnBinderReceivedListener binderListener =
            () -> runOnUiThread(this::refreshShizuku);
    private final Shizuku.OnBinderDeadListener deadListener =
            () -> runOnUiThread(this::refreshShizuku);

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateSlots();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("arkaplan", MODE_PRIVATE);
        for (int i = 0; i < 2; i++) {
            Slot.ALL[i].pkg = prefs.getString("pkg" + i, "");
            Slot.ALL[i].userId = prefs.getInt("user" + i, i == 0 ? 0 : 10);
        }

        buildUi();

        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(deadListener);
        Shizuku.addRequestPermissionResultListener(permListener);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        refreshShizuku();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizuku();
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeBinderDeadListener(deadListener);
        Shizuku.removeRequestPermissionResultListener(permListener);
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(bold ? 18 : 15);
        t.setPadding(0, dp(6), 0, dp(2));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button btn(String s, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setOnClickListener(l);
        return x;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(col);

        col.addView(text("Arka Plan (deneme sürümü)", true));
        col.addView(text("Test: oyunu başlat, ana ekrana çık, 30 sn sonra geri dön. "
                + "Kare sayısı artmışsa oyun arka planda da çalışıyor demektir.", false));

        shizukuStatus = text("", false);
        col.addView(shizukuStatus);
        col.addView(btn("Shizuku iznini ver", v -> askShizuku()));
        col.addView(btn("Çoklu pencere ayarlarını aç", v -> enableMultiWindow()));
        col.addView(btn("Kullanıcıları (profilleri) göster", v -> showUsers()));

        for (int i = 0; i < 2; i++) {
            final int idx = i;
            col.addView(text("— Oyun " + (i + 1) + " —", true));
            pickLabel[i] = text("", false);
            col.addView(pickLabel[i]);
            col.addView(btn("Uygulama seç", v -> pickApp(idx)));

            col.addView(text("Kullanıcı no (0 = normal, Shelter kopyası genelde 10):", false));
            userEdit[i] = new EditText(this);
            userEdit[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            userEdit[i].setText(String.valueOf(Slot.ALL[i].userId));
            col.addView(userEdit[i]);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button start = btn("Başlat", v -> startSlot(idx));
            Button stop = btn("Durdur", v -> stopSlot(idx));
            row.addView(start, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(stop, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            col.addView(row);

            slotStatus[i] = text("", false);
            col.addView(slotStatus[i]);
            updatePickLabel(i);
        }

        col.addView(text("Kayıt", true));
        log = text("", false);
        log.setTextIsSelectable(true);
        col.addView(log);

        setContentView(sv);
    }

    private void say(String s) {
        runOnUiThread(() -> log.setText(s));
    }

    private void updatePickLabel(int i) {
        String p = Slot.ALL[i].pkg;
        pickLabel[i].setText("Uygulama: " + (p.isEmpty() ? "seçilmedi" : p));
    }

    // ----------------------------------------------------------- Shizuku

    private boolean shizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshShizuku() {
        String s;
        try {
            if (!Shizuku.pingBinder()) {
                s = "Shizuku: ÇALIŞMIYOR. Shizuku uygulamasını aç ve başlat.";
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                s = "Shizuku: hazır ✔";
            } else {
                s = "Shizuku: çalışıyor ama bu uygulamaya izin verilmedi.";
            }
        } catch (Throwable t) {
            s = "Shizuku: hata " + t;
        }
        shizukuStatus.setText(s);
    }

    private void askShizuku() {
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_SHIZUKU);
            }
        } catch (Throwable t) {
            say("Shizuku hatası: " + t);
        }
        refreshShizuku();
    }

    private void enableMultiWindow() {
        if (!shizukuReady()) {
            say("Önce Shizuku'yu başlat ve izni ver.");
            return;
        }
        new Thread(() -> {
            String r = Sh.run("settings put global force_resizable_activities 1;"
                    + " settings put global enable_freeform_support 1;"
                    + " echo tamam");
            say("Çoklu pencere ayarları: " + r);
        }).start();
    }

    private void showUsers() {
        if (!shizukuReady()) {
            say("Önce Shizuku'yu başlat ve izni ver.");
            return;
        }
        new Thread(() -> say(Sh.run("pm list users"))).start();
    }

    // ------------------------------------------------------------- Slots

    private void pickApp(int idx) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        final List<String[]> items = new ArrayList<>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            items.add(new String[]{String.valueOf(ri.loadLabel(pm)), pkg});
        }
        Collections.sort(items, (a, c) -> a[0].compareToIgnoreCase(c[0]));
        String[] names = new String[items.size()];
        for (int k = 0; k < names.length; k++) {
            names[k] = items.get(k)[0] + "\n" + items.get(k)[1];
        }
        new AlertDialog.Builder(this)
                .setTitle("Oyunu seç")
                .setItems(names, (d, which) -> {
                    Slot.ALL[idx].pkg = items.get(which)[1];
                    prefs.edit().putString("pkg" + idx, Slot.ALL[idx].pkg).apply();
                    updatePickLabel(idx);
                })
                .show();
    }

    private void startSlot(int idx) {
        if (!shizukuReady()) {
            say("Önce Shizuku'yu başlat ve izni ver.");
            return;
        }
        final Slot s = Slot.ALL[idx];
        try {
            s.userId = Integer.parseInt(userEdit[idx].getText().toString().trim());
        } catch (NumberFormatException e) {
            s.userId = 0;
        }
        prefs.edit().putInt("user" + idx, s.userId).apply();
        startForegroundService(new Intent(this, KeepAliveService.class));
        say("Başlatılıyor...");
        new Thread(() -> say(s.start(getApplicationContext()))).start();
    }

    private void stopSlot(int idx) {
        final Slot s = Slot.ALL[idx];
        new Thread(() -> {
            s.stop();
            if (!Slot.anyRunning()) {
                stopService(new Intent(this, KeepAliveService.class));
            }
            say("Oyun " + (idx + 1) + " durduruldu.");
        }).start();
    }

    private void updateSlots() {
        long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < 2; i++) {
            Slot s = Slot.ALL[i];
            if (!s.running()) {
                slotStatus[i].setText("Durum: kapalı");
                lastFrames[i] = 0;
                lastTick[i] = now;
                continue;
            }
            long f = s.frames;
            long dt = Math.max(1, now - lastTick[i]);
            long fps = (f - lastFrames[i]) * 1000 / dt;
            lastFrames[i] = f;
            lastTick[i] = now;
            long since = s.lastFrameMs == 0 ? -1 : (now - s.lastFrameMs) / 1000;
            long up = (now - s.startMs) / 1000;
            slotStatus[i].setText("Durum: çalışıyor | sanal ekran " + s.displayId
                    + "\nSüre: " + up + " sn | Kare: " + f + " | ~" + fps + " kare/sn"
                    + "\nSon kare: " + (since < 0 ? "henüz yok" : since + " sn önce"));
        }
    }
}
