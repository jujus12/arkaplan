package com.arkaplan.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_SHIZUKU = 100;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String pkg = "";

    private TextView shizukuStatus;
    private TextView pickLabel;
    private TextView state;
    private TextView detailView;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (code, result) -> runOnUiThread(this::refresh);
    private final Shizuku.OnBinderReceivedListener binderListener =
            () -> runOnUiThread(this::refresh);
    private final Shizuku.OnBinderDeadListener deadListener =
            () -> runOnUiThread(this::refresh);

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("arkaplan", MODE_PRIVATE);
        pkg = prefs.getString("pkg", "");
        buildUi();
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(deadListener);
        Shizuku.addRequestPermissionResultListener(permListener);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(bold ? 18 : 15);
        t.setPadding(0, dp(8), 0, dp(2));
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

        col.addView(text("Arka Plan", true));
        col.addView(text("Seçtiğin uygulama ekranda küçük bir pencerede üstte durur. "
                + "Başka uygulamalara dokunsan da arka plana düşmez. "
                + "Pencerenin üstündeki çubuktan saydamlığı ayarlarsın.", false));

        shizukuStatus = text("", false);
        col.addView(shizukuStatus);
        col.addView(btn("Shizuku iznini ver", v -> askShizuku()));

        col.addView(text("1) Uygulama", true));
        pickLabel = text("", false);
        col.addView(pickLabel);
        col.addView(btn("Uygulama seç", v -> pickApp()));

        col.addView(text("2) Başlat", true));
        col.addView(btn("Pencereyi aç ve uygulamayı başlat", v -> startFloat()));
        col.addView(btn("Kapat", v -> stopService(new Intent(this, FloatService.class))));
        col.addView(btn("Eski ikinci ekranı kapat (Yer Paylaşımı / Overlay)", v -> {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Önce Shizuku'yu başlat ve izni ver.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            new Thread(() -> {
                String r = Sh.run("settings delete global overlay_display_devices;"
                        + " settings delete global force_resizable_activities;"
                        + " settings delete global enable_freeform_support; echo tamam");
                runOnUiThread(() -> Toast.makeText(this, "İkinci ekran kapatıldı: " + r,
                        Toast.LENGTH_LONG).show());
            }).start();
        }));
        state = text("", false);
        col.addView(state);
        detailView = text("", false);
        detailView.setTextSize(12);
        detailView.setTextIsSelectable(true);
        col.addView(detailView);

        col.addView(text("Not: Başlat, uygulamayı kapatıp yeniden açar (pencerede açılması için).",
                false));
        setContentView(sv);
        updatePick();
    }

    private void updatePick() {
        pickLabel.setText("Uygulama: " + (pkg.isEmpty() ? "seçilmedi" : pkg));
    }

    private void refresh() {
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
        state.setText("Durum: " + FloatService.status);
        String d = FloatService.detail;
        if (!d.contentEquals(detailView.getText())) detailView.setText(d);
    }

    private void askShizuku() {
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_SHIZUKU);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Shizuku hatası: " + t, Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void pickApp() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        final List<String[]> items = new ArrayList<>();
        for (ResolveInfo ri : list) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName())) continue;
            items.add(new String[]{String.valueOf(ri.loadLabel(pm)), p});
        }
        Collections.sort(items, (a, c) -> a[0].compareToIgnoreCase(c[0]));
        String[] names = new String[items.size()];
        for (int k = 0; k < names.length; k++) {
            names[k] = items.get(k)[0] + "\n" + items.get(k)[1];
        }
        new AlertDialog.Builder(this)
                .setTitle("Uygulamayı seç")
                .setItems(names, (d, which) -> {
                    pkg = items.get(which)[1];
                    prefs.edit().putString("pkg", pkg).apply();
                    updatePick();
                })
                .show();
    }

    private void startFloat() {
        if (pkg.isEmpty()) {
            Toast.makeText(this, "Önce uygulama seç.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Üstte gösterme izni lazım. Arka Plan'a izin ver, "
                    + "geri gelip tekrar bas.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Önce Shizuku'yu başlat ve izni ver.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Shizuku hatası: " + t, Toast.LENGTH_LONG).show();
            return;
        }
        stopService(new Intent(this, FloatService.class));
        Intent i = new Intent(this, FloatService.class)
                .putExtra("pkg", pkg)
                .putExtra("w", 1280)
                .putExtra("h", 720)
                .putExtra("dpi", 240);
        startForegroundService(i);
    }
}
