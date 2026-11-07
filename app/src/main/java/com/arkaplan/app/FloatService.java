package com.arkaplan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Floating always-on-top window. The chosen app runs on a trusted virtual display whose output
 * is drawn into this window, so it keeps running no matter what else the user touches.
 */
public class FloatService extends Service {
    public static final String ACTION_STOP = "com.arkaplan.app.STOP";
    private static final String CHANNEL = "arkaplan";
    private static final int VERSION = 3;

    public static volatile String status = "Kapalı";
    public static volatile String detail = "";
    public static volatile boolean running = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout root;
    private SurfaceView sv;
    private TextView title;

    private Shizuku.UserServiceArgs args;
    private volatile IVdService svc;
    private volatile Surface surface;
    private volatile int displayId = -1;
    private boolean created = false;

    private String pkg = "";
    private int dw = 1280, dh = 720, ddpi = 240;
    private int winWidthPx;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder != null && binder.pingBinder()) {
                svc = IVdService.Stub.asInterface(binder);
                setStatus("Shizuku servisi bağlandı.");
                tryCreate();
            } else {
                diagnose("Servis bağlandı ama yanıt vermiyor (binder "
                        + (binder == null ? "yok" : "ölü") + ").");
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            diagnose("Servis süreci öldü (onBindingDied).");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            diagnose("Servis boş döndü (onNullBinding).");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
            created = false;
            displayId = -1;
            setStatus("Shizuku servisi koptu.");
        }
    };

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
        detail = "";
        setStatus("Pencere açılıyor...");
        buildWindow();
        bindShizuku();
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
        final IVdService s = svc;
        final Shizuku.UserServiceArgs a = args;
        bg.execute(() -> {
            try {
                if (s != null) s.release();
            } catch (Throwable ignored) {
            }
            try {
                if (a != null) Shizuku.unbindUserService(a, conn, true);
            } catch (Throwable ignored) {
            }
        });
        bg.shutdown();
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

    private void toast(String s) {
        ui.post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show());
    }

    private void buildWindow() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        winWidthPx = dp(420);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xCC202020);

        // top bar: drag handle / status, transparency, smaller, bigger, close
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
                if (sv != null) sv.setAlpha(0.2f + p / 100f);
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
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                if (created) {
                    final IVdService s = svc;
                    final Surface sf = surface;
                    bg.execute(() -> {
                        try {
                            if (s != null) s.setSurface(sf);
                        } catch (Throwable ignored) {
                        }
                    });
                } else {
                    tryCreate();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surface = null;
                final IVdService s = svc;
                if (created && s != null) {
                    bg.execute(() -> {
                        try {
                            s.setSurface(null);
                        } catch (Throwable ignored) {
                        }
                    });
                }
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
        final IVdService s = svc;
        if (s == null || displayId < 0) return true;
        int act;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                act = MotionEvent.ACTION_DOWN;
                break;
            case MotionEvent.ACTION_MOVE:
                act = MotionEvent.ACTION_MOVE;
                break;
            case MotionEvent.ACTION_UP:
                act = MotionEvent.ACTION_UP;
                break;
            case MotionEvent.ACTION_CANCEL:
                act = MotionEvent.ACTION_CANCEL;
                break;
            default:
                return true;
        }
        float x = e.getX() * dw / Math.max(1, v.getWidth());
        float y = e.getY() * dh / Math.max(1, v.getHeight());
        try {
            s.touch(displayId, act, x, y, e.getDownTime());
        } catch (Throwable ignored) {
        }
        return true;
    }

    // ------------------------------------------------------------ Shizuku

    private void bindShizuku() {
        try {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission()
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                setStatus("Shizuku hazır değil (başlat ve izin ver).");
                toast("Shizuku hazır değil. Ana ekranda Shizuku iznini ver.");
                return;
            }
            args = new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), VdService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("vd")
                    .debuggable(false)
                    .version(VERSION);
            Shizuku.bindUserService(args, conn);
            setStatus("Shizuku servisine bağlanılıyor...");
            ui.postDelayed(() -> {
                if (running && svc == null) diagnose("Servis 12 sn içinde bağlanmadı.");
            }, 12000);
        } catch (Throwable t) {
            setStatus("Shizuku hatası: " + t);
        }
    }

    private void diagnose(String why) {
        setStatus("Bağlantı sorunu");
        if (bg.isShutdown()) return;
        bg.execute(() -> {
            StringBuilder sb = new StringBuilder(why).append("\n");
            try {
                sb.append("Shizuku sürüm: ").append(Shizuku.getVersion())
                        .append(", uid: ").append(Shizuku.getUid()).append("\n");
            } catch (Throwable t) {
                sb.append("Shizuku bilgisi alınamadı: ").append(t).append("\n");
            }
            String log = Sh.run("logcat -d -t 600 | grep -i -E "
                    + "'arkaplan|AndroidRuntime|FATAL|VdService|UserService|ServiceStarter'"
                    + " | tail -n 14");
            sb.append("Kayıt:\n").append(log.isEmpty() ? "(ilgili satır yok)" : log);
            detail = sb.toString();
        });
    }

    private synchronized void tryCreate() {
        final IVdService s = svc;
        final Surface sf = surface;
        if (s == null || sf == null || created) return;
        created = true;
        bg.execute(() -> {
            try {
                setStatus("Ekran oluşturuluyor...");
                String r = s.createDisplay(sf, dw, dh, ddpi);
                if (r == null || !r.startsWith("OK:")) {
                    created = false;
                    setStatus("Ekran hatası");
                    detail = r == null ? "Ekran oluşturulamadı" : r;
                    toast(r == null ? "Ekran oluşturulamadı" : r);
                    return;
                }
                String[] p = r.split(":");
                displayId = Integer.parseInt(p[1]);
                setStatus("Ekran " + displayId + " hazır, oyun açılıyor...");
                launchApp();
            } catch (Throwable t) {
                created = false;
                setStatus("Hata: " + t);
                toast("Hata: " + t);
            }
        });
    }

    private void launchApp() {
        // -S restarts the app so it really opens on the new display
        String out = Sh.run("am start -S --user 0 --display " + displayId
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + pkg);
        String low = out.toLowerCase();
        if (low.contains("exception") || low.contains("error")) {
            setStatus("Uygulama açılamadı");
            detail = out;
            toast("Uygulama açılamadı:\n" + out);
        } else {
            setStatus("Çalışıyor (ekran " + displayId + ")");
        }
    }
}
