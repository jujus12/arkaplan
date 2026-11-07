package com.arkaplan.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Runs inside the Shizuku (shell uid) process. Creates a TRUSTED virtual display, the same way
 * scrcpy's "--new-display" does, so that other apps' activities may run on it, and injects
 * touches into it.
 */
public class VdService extends IVdService.Stub {
    private static final String SHELL = "com.android.shell";

    // VirtualDisplay flag bits (some are hidden APIs)
    private static final int PUBLIC = 1;
    private static final int PRESENTATION = 1 << 1;
    private static final int OWN_CONTENT_ONLY = 1 << 3;
    private static final int SUPPORTS_TOUCH = 1 << 6;
    private static final int ROTATES_WITH_CONTENT = 1 << 7;
    private static final int DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int TRUSTED = 1 << 10;
    private static final int OWN_DISPLAY_GROUP = 1 << 11;
    private static final int ALWAYS_UNLOCKED = 1 << 12;
    private static final int TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int OWN_FOCUS = 1 << 14;
    private static final int DEVICE_DISPLAY_GROUP = 1 << 15;

    private Handler handler;
    private VirtualDisplay vd;
    private volatile String lastError = "";

    private Object input;
    private Method inject;
    private int injectArgs;
    private Method setDisplayId;

    public VdService() {
        final CountDownLatch ready = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            // Never touch the main looper here: Shizuku's starter prepares it itself.
            Looper.prepare();
            handler = new Handler(Looper.myLooper());
            ready.countDown();
            Looper.loop();
        }, "arkaplan-vd");
        t.start();
        try {
            ready.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    // ---------------------------------------------------------- self test

    private static String exec(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            java.io.InputStream in = p.getInputStream();
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor();
            return bo.toString("UTF-8").trim();
        } catch (Throwable t) {
            return "exec hatası: " + t;
        }
    }

    /**
     * Run by hand through Shizuku:
     * CLASSPATH=base.apk app_process /system/bin com.arkaplan.app.VdService [package]
     * Creates the trusted display, optionally launches the package on it, prints every step.
     */
    public static void main(String[] args) {
        final java.io.PrintStream out = System.out;
        Thread wd = new Thread(() -> {
            try {
                Thread.sleep(40000);
            } catch (InterruptedException ignored) {
            }
            System.exit(2);
        });
        wd.setDaemon(true);
        wd.start();
        try {
            out.println("1) sınıf yüklendi. Android API " + Build.VERSION.SDK_INT
                    + ", uid " + android.os.Process.myUid());
            VdService s = new VdService();
            out.println("2) servis nesnesi oluştu");
            android.media.ImageReader r = android.media.ImageReader.newInstance(
                    1280, 720, android.graphics.PixelFormat.RGBA_8888, 2);
            String res = s.createDisplay(r.getSurface(), 1280, 720, 240);
            out.println("3) ekran: " + res);
            if (res.startsWith("OK:") && args.length > 0) {
                int id = Integer.parseInt(res.split(":")[1]);
                String comp = exec("cmd package resolve-activity --brief --user 0"
                        + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
                        + args[0] + " | tail -n 1");
                out.println("4a) bileşen: " + comp);
                out.println("4b) uygulama başlatma:\n" + exec("am start -S --user 0 --display "
                        + id + " -n " + comp));
                Thread.sleep(5000);
                String d = exec("dumpsys activity activities");
                int shown = 0;
                out.println("5) etkinlik durumu:");
                for (String line : d.split("\n")) {
                    String l = line.trim();
                    if (shown < 12 && (l.startsWith("Display #")
                            || l.contains("topResumedActivity")
                            || l.contains("ResumedActivity:"))) {
                        out.println("   " + l);
                        shown++;
                    }
                }
            }
            s.release();
            out.println("6) bitti");
        } catch (Throwable t) {
            t.printStackTrace(out);
        }
        System.exit(0);
    }

    // ------------------------------------------------------------ helpers

    private <T> T call(Callable<T> c) throws Throwable {
        FutureTask<T> task = new FutureTask<>(c);
        handler.post(task);
        try {
            return task.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private static String describe(Throwable t) {
        while ((t instanceof InvocationTargetException) && t.getCause() != null) t = t.getCause();
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(4, st.length); i++) sb.append("\n  ").append(st[i]);
        return sb.toString();
    }

    private static final class Fake extends ContextWrapper {
        Fake(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return SHELL;
        }

        // hidden API on Context: no @Override so it compiles against the public SDK
        public String getOpPackageName() {
            return SHELL;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }

    private Context systemContext() throws Exception {
        Class<?> atc = Class.forName("android.app.ActivityThread");
        Object at = atc.getDeclaredMethod("currentActivityThread").invoke(null);
        if (at == null) {
            try {
                Constructor<?> ctor = atc.getDeclaredConstructor();
                ctor.setAccessible(true);
                at = ctor.newInstance();
                Field f = atc.getDeclaredField("sCurrentActivityThread");
                f.setAccessible(true);
                f.set(null, at);
            } catch (Throwable first) {
                Method sm = atc.getDeclaredMethod("systemMain");
                sm.setAccessible(true);
                at = sm.invoke(null);
            }
        }
        Method gsc = atc.getDeclaredMethod("getSystemContext");
        gsc.setAccessible(true);
        return (Context) gsc.invoke(at);
    }

    private static int[] flagSets() {
        int base = PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH
                | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL;
        List<Integer> l = new ArrayList<>();
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= 34) {
            l.add(base | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED
                    | TOUCH_FEEDBACK_DISABLED | OWN_FOCUS | DEVICE_DISPLAY_GROUP);
        }
        if (sdk >= 33) {
            l.add(base | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED);
            l.add(base | TRUSTED | OWN_DISPLAY_GROUP);
            l.add(base | TRUSTED);
        }
        l.add(base);
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    // ---------------------------------------------------------------- API

    @Override
    public String createDisplay(Surface surface, int w, int h, int dpi) {
        try {
            return call(() -> doCreate(surface, w, h, dpi));
        } catch (Throwable t) {
            lastError = describe(t);
            return "HATA: " + lastError;
        }
    }

    private String doCreate(Surface surface, int w, int h, int dpi) throws Exception {
        if (vd != null) {
            vd.release();
            vd = null;
        }
        Context fake = new Fake(systemContext());
        Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        DisplayManager dm = c.newInstance(fake);

        Throwable last = null;
        for (int fl : flagSets()) {
            try {
                VirtualDisplay d = dm.createVirtualDisplay("arkaplan", w, h, dpi, surface, fl);
                if (d != null) {
                    vd = d;
                    return "OK:" + d.getDisplay().getDisplayId() + ":" + fl;
                }
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new RuntimeException("Ekran oluşturulamadı: "
                + (last == null ? "bilinmeyen" : describe(last)));
    }

    @Override
    public void setSurface(Surface surface) {
        try {
            call(() -> {
                if (vd != null) vd.setSurface(surface);
                return null;
            });
        } catch (Throwable t) {
            lastError = describe(t);
        }
    }

    @Override
    public void touch(int displayId, int action, float x, float y, long downTime) {
        try {
            if (inject == null) initInput();
            MotionEvent ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    action, x, y, 0);
            ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            setDisplayId.invoke(ev, displayId);
            if (injectArgs == 2) {
                inject.invoke(input, ev, 0);
            } else {
                inject.invoke(input, ev, 0, -1);
            }
            ev.recycle();
        } catch (Throwable t) {
            lastError = "dokunuş: " + describe(t);
        }
    }

    private void initInput() throws Exception {
        String[] names = {"android.hardware.input.InputManagerGlobal",
                "android.hardware.input.InputManager"};
        Throwable last = null;
        for (String n : names) {
            try {
                Class<?> cls = Class.forName(n);
                Method gi = cls.getDeclaredMethod("getInstance");
                gi.setAccessible(true);
                Object inst = gi.invoke(null);
                Method m;
                int args;
                try {
                    m = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
                    args = 2;
                } catch (NoSuchMethodException e) {
                    m = cls.getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
                    args = 3;
                }
                input = inst;
                inject = m;
                injectArgs = args;
                setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
                return;
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new Exception("InputManager bulunamadı: " + (last == null ? "" : describe(last)));
    }

    @Override
    public void release() {
        try {
            call(() -> {
                if (vd != null) vd.release();
                vd = null;
                return null;
            });
        } catch (Throwable t) {
            lastError = describe(t);
        }
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public void destroy() {
        release();
        System.exit(0);
    }
}
