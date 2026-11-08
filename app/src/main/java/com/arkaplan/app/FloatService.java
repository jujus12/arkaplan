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
import android.widget.FrameLayout;
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
 * Floating always-on-top windows ("panes"). Each pane runs one app on its own trusted virtual
 * display that lives in a Shizuku (shell) process; that process streams the picture to the pane
 * and receives the touches. Because the app runs on its own display it never goes to the
 * background. A pane can be switched to click-through mode: the picture stays on screen but
 * touches go to whatever is behind it.
 */
public class FloatService extends Service {
    public static final String ACTION_START = "com.arkaplan.app.START";
    public static final String ACTION_STOP = "com.arkaplan.app.STOP";
    public static final int SLOTS = 2;

    private static final String CHANNEL = "arkaplan";
    private static final String KILL = "pkill -f 'arkaplan.app.[V]dService'";

    public static final String[] status = {"Kapalı", "Kapalı"};
    public static final String[] detail = {"", ""};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Pane[] panes = new Pane[SLOTS];

    // ----------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Arka Plan", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (intent == null) {
            if (noPanes()) stopSelf();
            return START_NOT_STICKY;
        }
        int slot = intent.getIntExtra("slot", -1);

        if (ACTION_STOP.equals(intent.getAction())) {
            if (slot >= 0 && slot < SLOTS) {
                endPane(panes[slot]);
            } else {
                for (int i = 0; i < SLOTS; i++) endPane(panes[i]);
            }
            if (noPanes()) stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction()) && slot >= 0 && slot < SLOTS) {
            String pkg = intent.getStringExtra("pkg");
            endPane(panes[slot]);
            if (pkg == null || pkg.isEmpty()) {
                status[slot] = "Uygulama seçilmedi.";
            } else if (usedElsewhere(slot, pkg)) {
                status[slot] = "Bu uygulama diğer pencerede zaten çalışıyor.";
                toast("Aynı uygulama iki pencerede açılamaz. Başka bir uygulama seç.");
            } else {
                boolean alone = noPanes();
                Pane p = new Pane(slot, pkg, intent.getIntExtra("w", 1280),
                        intent.getIntExtra("h", 720), intent.getIntExtra("dpi", 240), alone);
                panes[slot] = p;
                p.open();
            }
            if (noPanes()) stopSelf();
        }
        return START_NOT_STICKY;
    }

    private boolean usedElsewhere(int slot, String pkg) {
        for (int i = 0; i < SLOTS; i++) {
            if (i != slot && panes[i] != null && pkg.equals(panes[i].pkg)) return true;
        }
        return false;
    }

    private boolean noPanes() {
        for (Pane p : panes) if (p != null) return false;
        return true;
    }

    /** Closes a pane (safe to call with null or twice). */
    private void endPane(Pane p) {
        if (p == null) return;
        if (panes[p.slot] == p) panes[p.slot] = null;
        p.close();
    }

    private void paneEndedByUser(Pane p) {
        endPane(p);
        if (noPanes()) stopSelf();
    }

    private void goForeground() {
        Intent stop = new Intent(this, FloatService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Arka Plan çalışıyor")
                .setContentText("Hepsini kapatmak için dokun")
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
        for (int i = 0; i < SLOTS; i++) endPane(panes[i]);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show());
    }

    // ----------------------------------------------------------------- pane

    private class Pane {
        final int slot;
        final String pkg;
        final int dw, dh, ddpi;
        final boolean cleanFirst;
        final int barH = dp(36);

        WindowManager wm;
        LinearLayout bar;
        FrameLayout viewBox;
        SurfaceView sv;
        TextView title;
        Button touchBtn;
        WindowManager.LayoutParams lpBar, lpView;
        int x, y, widthPx;
        float alphaVal = 1f;
        boolean touchOn = true;
        boolean passHinted = false;

        volatile SurfaceHolder holder;
        volatile Process proc;
        volatile OutputStream procIn;
        volatile boolean failed = false;
        volatile boolean closed = false;
        final ExecutorService writer = Executors.newSingleThreadExecutor();
        Bitmap bmp;
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        Pane(int slot, String pkg, int w, int h, int dpi, boolean cleanFirst) {
            this.slot = slot;
            this.pkg = pkg;
            this.dw = w;
            this.dh = h;
            this.ddpi = dpi;
            this.cleanFirst = cleanFirst;
        }

        void open() {
            detail[slot] = "";
            setStatus("Pencere açılıyor...");
            buildWindows();
            startStream();
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                if (viewBox != null) wm.removeView(viewBox);
            } catch (Throwable ignored) {
            }
            try {
                if (bar != null) wm.removeView(bar);
            } catch (Throwable ignored) {
            }
            viewBox = null;
            bar = null;
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
            }).start();
            writer.shutdown();
            status[slot] = "Kapalı";
        }

        void setStatus(String s) {
            status[slot] = s;
            ui.post(() -> {
                if (title != null && !closed) title.setText(" " + s);
            });
        }

        void addDetail(String s) {
            synchronized (detail) {
                detail[slot] = detail[slot].isEmpty() ? s : detail[slot] + "\n" + s;
            }
        }

        // ------------------------------------------------------------ windows

        int flagsBase() {
            return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        }

        WindowManager.LayoutParams newLp() {
            WindowManager.LayoutParams l = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flagsBase(),
                    PixelFormat.TRANSLUCENT);
            l.gravity = Gravity.TOP | Gravity.START;
            return l;
        }

        void buildWindows() {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            widthPx = dp(420);
            int vh = widthPx * dh / dw;
            x = Math.max(0, getResources().getDisplayMetrics().widthPixels - widthPx - dp(16))
                    - slot * dp(24);
            y = dp(48) + slot * (barH + vh + dp(10));

            // --- the picture (can become click-through)
            viewBox = new FrameLayout(FloatService.this);
            viewBox.setBackgroundColor(0xFF000000);
            sv = new SurfaceView(FloatService.this);
            sv.getHolder().setFixedSize(dw, dh);
            sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder h) {
                    holder = h;
                }

                @Override
                public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder h) {
                    holder = null;
                }
            });
            sv.setOnTouchListener(this::onSurfaceTouch);
            viewBox.addView(sv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            // --- the bar (always touchable)
            bar = new LinearLayout(FloatService.this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xFF202020);

            title = new TextView(FloatService.this);
            title.setText(" " + (slot + 1) + ": Taşı");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(11);
            title.setSingleLine(true);
            bar.addView(title, new LinearLayout.LayoutParams(0, barH, 1));

            SeekBar alpha = new SeekBar(FloatService.this);
            alpha.setMax(80);
            alpha.setProgress(80);
            alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    alphaVal = 0.2f + p / 100f;
                    applyView();
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            bar.addView(alpha, new LinearLayout.LayoutParams(dp(90), barH));

            touchBtn = smallBtn("👆", v -> toggleTouch());
            bar.addView(touchBtn);
            bar.addView(smallBtn("−", v -> resize(0.8f)));
            bar.addView(smallBtn("+", v -> resize(1.25f)));
            bar.addView(smallBtn("✕", v -> paneEndedByUser(this)));

            final float[] st = new float[4];
            title.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        st[0] = e.getRawX();
                        st[1] = e.getRawY();
                        st[2] = x;
                        st[3] = y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        x = (int) (st[2] + e.getRawX() - st[0]);
                        y = (int) (st[3] + e.getRawY() - st[1]);
                        applyLayout();
                        return true;
                    default:
                        return true;
                }
            });

            lpView = newLp();
            lpBar = newLp();
            applyLayoutValues();
            wm.addView(viewBox, lpView);
            wm.addView(bar, lpBar);
        }

        Button smallBtn(String t, View.OnClickListener l) {
            Button b = new Button(FloatService.this);
            b.setText(t);
            b.setAllCaps(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding(0, 0, 0, 0);
            b.setOnClickListener(l);
            b.setLayoutParams(new LinearLayout.LayoutParams(dp(38), barH));
            return b;
        }

        void applyLayoutValues() {
            int vh = widthPx * dh / dw;
            lpBar.x = x;
            lpBar.y = y;
            lpBar.width = widthPx;
            lpBar.height = barH;
            lpView.x = x;
            lpView.y = y + barH;
            lpView.width = widthPx;
            lpView.height = vh;
            lpView.flags = flagsBase()
                    | (touchOn ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            // Android 12+ drops touches that pass through a window more opaque than 80 %.
            lpView.alpha = touchOn ? alphaVal : Math.min(alphaVal, 0.8f);
            lpBar.alpha = 1f;
        }

        void applyLayout() {
            if (closed || bar == null || viewBox == null) return;
            applyLayoutValues();
            try {
                wm.updateViewLayout(viewBox, lpView);
                wm.updateViewLayout(bar, lpBar);
            } catch (Throwable ignored) {
            }
        }

        void applyView() {
            if (closed || viewBox == null) return;
            applyLayoutValues();
            try {
                wm.updateViewLayout(viewBox, lpView);
            } catch (Throwable ignored) {
            }
        }

        void resize(float f) {
            widthPx = Math.max(dp(300), Math.min(dp(1000), (int) (widthPx * f)));
            applyLayout();
        }

        void toggleTouch() {
            touchOn = !touchOn;
            touchBtn.setText(touchOn ? "👆" : "🚫");
            applyView();
            if (!touchOn && !passHinted) {
                passHinted = true;
                toast("Bu pencere artık dokunuşları almaz, arkadaki ekrana geçer "
                        + "(saydamlık en çok %80). Geri açmak için 🚫 düğmesine bas.");
            }
        }

        boolean onSurfaceTouch(View v, MotionEvent e) {
            final OutputStream o = procIn;
            if (o == null) return true;
            int act = e.getActionMasked();
            if (act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_MOVE
                    && act != MotionEvent.ACTION_UP && act != MotionEvent.ACTION_CANCEL) {
                return true;
            }
            float tx = e.getX() * dw / Math.max(1, v.getWidth());
            float ty = e.getY() * dh / Math.max(1, v.getHeight());
            final byte[] line = ("t " + act + " " + tx + " " + ty + " " + e.getDownTime() + "\n")
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

        void startStream() {
            Thread t = new Thread(() -> {
                try {
                    if (cleanFirst) Sh.run(KILL);
                    String cmd = "CLASSPATH=" + getPackageCodePath()
                            + " exec app_process /system/bin " + VdService.class.getName()
                            + " run " + pkg + " " + dw + " " + dh + " " + ddpi;
                    final Process p = Sh.spawn(cmd);
                    if (closed) {
                        p.destroy();
                        return;
                    }
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
                    }, "arkaplan-err" + slot);
                    err.setDaemon(true);
                    err.start();

                    readLoop(p.getInputStream());
                } catch (Throwable th) {
                    failed = true;
                    setStatus("Hata");
                    addDetail("Başlatma hatası: " + th);
                } finally {
                    if (!closed && !failed) setStatus("Bağlantı koptu");
                }
            }, "arkaplan-read" + slot);
            t.start();
        }

        void readLoop(InputStream is) throws Exception {
            DataInputStream in = new DataInputStream(
                    new java.io.BufferedInputStream(is, 1 << 16));
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

        void handleText(String s) {
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

        void drawFrame(byte[] buf, int len) {
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
}
