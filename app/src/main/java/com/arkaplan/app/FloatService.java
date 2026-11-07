package com.arkaplan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating always-on-top window. The chosen app runs on a trusted virtual display that lives in
 * a Shizuku (shell) process; that process streams the picture to this window and receives the
 * touches. Because the app runs on its own display it never goes to the background.
 */
public class FloatService extends Service {
    public static final String ACTION_STOP = "com.arkaplan.app.STOP";
    private static final String CHANNEL = "arkaplan";
    private static final String KILL = "pkill -f 'arkaplan.app.[V]dService'";

    public static volatile String status = "Kapalı";
    public static volatile String detail = "";
    public static volatile boolean running = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout root;
    private SurfaceView sv;
    private TextView title;

    private volatile SurfaceHolder holder;
    private volatile Process proc;
    private volatile OutputStream procIn;
    private volatile boolean failed = false;

    private String pkg = "";
    private int dw = 1280, dh = 720, ddpi = 240;
    private int winWidthPx;
    private Bitmap bmp;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // ----------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Arka Plan", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        if (root != null || intent == null) return START_NOT_STICKY;

        pkg = intent.getStringExtra("pkg");
        dw = intent.getIntExtra("w", 1280);
        dh = intent.getIntExtra("h", 720);
        ddpi = intent.getIntExtra("dpi", 240);
        if (pkg == null || pkg.isEmpty()) {
            setStatus("Uygulama seçilmedi.");
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        failed = false;
        detail = "";
        setStatus("Pencere açılıyor...");
        buildWindow();
        startStream();
        return START_NOT_STICKY;
    }

    private void goForeground() {
        Intent stop = new Intent(this, FloatService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Arka Plan çalışıyor")
                .setContentText("Kapatmak için dokun")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (root != null) wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
        final Process p = proc;
        final OutputStream o = procIn;
        new Thread(() -> {
            try {
                if (o != null) {
                    o.write("q\n".getBytes(StandardCharsets.UTF_8));
                    o.flush();
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
            Sh.run(KILL);
        }).start();
        writer.shutdown();
        status = "Kapalı";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------- window

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void setStatus(String s) {
        status = s;
        ui.post(() -> {
            if (title != null) title.setText(" " + s);
        });
    }

    private synchronized void addDetail(String s) {
        detail = detail.isEmpty() ? s : detail + "\n" + s;
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show());
    }

    private void buildWindow() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        winWidthPx = dp(420);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF202020);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        title = new TextView(this);
        title.setText(" Taşı");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(11);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));

        SeekBar alpha = new SeekBar(this);
        alpha.setMax(80);
        alpha.setProgress(80);
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (lp != null && root != null) {
                    lp.alpha = 0.2f + p / 100f;
                    try {
                        wm.updateViewLayout(root, lp);
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        bar.addView(alpha, new LinearLayout.LayoutParams(dp(110), dp(36)));

        bar.addView(smallBtn("−", v -> resize(0.8f)));
        bar.addView(smallBtn("+", v -> resize(1.25f)));
        bar.addView(smallBtn("✕", v -> stopSelf()));
        root.addView(bar);

        sv = new SurfaceView(this);
        sv.getHolder().setFixedSize(dw, dh);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                holder = h;
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                holder = null;
            }
        });
        sv.setOnTouchListener(this::onSurfaceTouch);
        root.addView(sv, new LinearLayout.LayoutParams(winWidthPx, winWidthPx * dh / dw));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(0, getResources().getDisplayMetrics().widthPixels - winWidthPx - dp(16));
        lp.y = dp(48);

        final float[] st = new float[4];
        title.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX();
                    st[1] = e.getRawY();
                    st[2] = lp.x;
                    st[3] = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = (int) (st[2] + e.getRawX() - st[0]);
                    lp.y = (int) (st[3] + e.getRawY() - st[1]);
                    wm.updateViewLayout(root, lp);
                    return true;
                default:
                    return true;
            }
        });

        wm.addView(root, lp);
    }

    private Button smallBtn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(36)));
        return b;
    }

    private void resize(float f) {
        winWidthPx = Math.max(dp(200), Math.min(dp(1000), (int) (winWidthPx * f)));
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) sv.getLayoutParams();
        p.width = winWidthPx;
        p.height = winWidthPx * dh / dw;
        sv.setLayoutParams(p);
        wm.updateViewLayout(root, lp);
    }

    private boolean onSurfaceTouch(View v, MotionEvent e) {
        final OutputStream o = procIn;
        if (o == null) return true;
        int act = e.getActionMasked();
        if (act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_MOVE
                && act != MotionEvent.ACTION_UP && act != MotionEvent.ACTION_CANCEL) {
            return true;
        }
        float x = e.getX() * dw / Math.max(1, v.getWidth());
        float y = e.getY() * dh / Math.max(1, v.getHeight());
        final byte[] line = ("t " + act + " " + x + " " + y + " " + e.getDownTime() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try {
            writer.execute(() -> {
                try {
                    o.write(line);
                    o.flush();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
        return true;
    }

    // ------------------------------------------------------------- stream

    private void startStream() {
        Thread t = new Thread(() -> {
            try {
                Sh.run(KILL);
                String cmd = "CLASSPATH=" + getPackageCodePath()
                        + " exec app_process /system/bin " + VdService.class.getName()
                        + " run " + pkg + " " + dw + " " + dh + " " + ddpi;
                final Process p = Sh.spawn(cmd);
                proc = p;
                procIn = p.getOutputStream();
                setStatus("Ekran kuruluyor...");

                Thread err = new Thread(() -> {
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(p.getErrorStream()));
                        StringBuilder sb = new StringBuilder();
                        String l;
                        while ((l = r.readLine()) != null) {
                            if (sb.length() < 2500) sb.append(l).append('\n');
                        }
                        if (sb.length() > 0) addDetail("Süreç çıktısı:\n" + sb);
                    } catch (Throwable ignored) {
                    }
                }, "arkaplan-err");
                err.setDaemon(true);
                err.start();

                readLoop(p.getInputStream());
            } catch (Throwable th) {
                failed = true;
                setStatus("Hata");
                addDetail("Başlatma hatası: " + th);
            } finally {
                if (running && !failed) setStatus("Bağlantı koptu");
            }
        }, "arkaplan-read");
        t.start();
    }

    private void readLoop(InputStream is) throws Exception {
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(is, 1 << 16));
        byte[] buf = new byte[0];
        while (true) {
            int type = in.read();
            if (type < 0) break;
            int len = in.readInt();
            if (len < 0 || len > 64 * 1024 * 1024) throw new java.io.IOException("bozuk paket");
            if (buf.length < len) buf = new byte[len];
            in.readFully(buf, 0, len);
            if (type == 1) {
                handleText(new String(buf, 0, len, StandardCharsets.UTF_8));
            } else if (type == 2) {
                drawFrame(buf, len);
            }
        }
    }

    private void handleText(String s) {
        if (s.startsWith("ST:")) {
            setStatus(s.substring(3));
        } else if (s.startsWith("ER:")) {
            failed = true;
            setStatus("Hata");
            addDetail(s.substring(3));
            toast(s.substring(3));
        } else if (s.startsWith("WN:")) {
            addDetail(s.substring(3));
        }
    }

    private void drawFrame(byte[] buf, int len) {
        SurfaceHolder hd = holder;
        if (hd == null || len < 8) return;
        ByteBuffer hb = ByteBuffer.wrap(buf);
        int w = hb.getInt(0);
        int h = hb.getInt(4);
        if (w <= 0 || h <= 0 || len < 8 + w * h * 4) return;
        if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(buf, 8, w * h * 4));
        Canvas c = hd.lockCanvas();
        if (c == null) return;
        try {
            c.drawBitmap(bmp, null, new Rect(0, 0, c.getWidth(), c.getHeight()), paint);
        } finally {
            hd.unlockCanvasAndPost(c);
        }
    }
}
