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
        if (args.length > 0 && args[0].equals("run")) {
            runStream(args);
            return;
        }
        selfTest(args.length > 1 ? new String[]{args[1]} : new String[0]);
    }

    // ---------------------------------------------------------- stream mode

    private static void send(java.io.OutputStream os, Object lock, int type,
                             byte[] data, int off, int len) {
        try {
            synchronized (lock) {
                os.write(type);
                os.write(len >>> 24);
                os.write(len >>> 16);
                os.write(len >>> 8);
                os.write(len);
                os.write(data, off, len);
                os.flush();
            }
        } catch (Throwable t) {
            System.exit(0); // the app is gone
        }
    }

    private static void sendText(java.io.OutputStream os, Object lock, String prefix, String s) {
        byte[] b = (prefix + s).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        send(os, lock, 1, b, 0, b.length);
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    /**
     * Long-running mode, started by the app through Shizuku:
     * app_process ... VdService run PACKAGE W H DPI
     * stdout: packets [type:1][len:4][payload] (1 = text, 2 = frame: w, h, RGBA pixels)
     * stdin : lines "t ACTION X Y DOWNTIME" (touch) and "q" (quit)
     */
    private static void runStream(String[] a) {
        final java.io.OutputStream os = new java.io.BufferedOutputStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out), 1 << 16);
        System.setOut(System.err);
        final Object lock = new Object();
        try {
            final String pkg = a[1];
            final int w = Integer.parseInt(a[2]);
            final int h = Integer.parseInt(a[3]);
            final int dpi = Integer.parseInt(a[4]);
            sendText(os, lock, "ST:", "Ekran kuruluyor...");
            final VdService s = new VdService();

            android.os.HandlerThread ht = new android.os.HandlerThread("frames");
            ht.start();
            final android.media.ImageReader reader = android.media.ImageReader.newInstance(
                    w, h, android.graphics.PixelFormat.RGBA_8888, 3);
            final long[] last = {0};
            final boolean[] first = {true};
            final byte[][] fb = new byte[1][];
            reader.setOnImageAvailableListener(r -> {
                android.media.Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    long now = SystemClock.uptimeMillis();
                    if (now - last[0] < 66) return; // about 15 frames per second
                    last[0] = now;
                    android.media.Image.Plane p = img.getPlanes()[0];
                    java.nio.ByteBuffer b = p.getBuffer();
                    int rs = p.getRowStride();
                    int need = 8 + w * h * 4;
                    if (fb[0] == null || fb[0].length != need) fb[0] = new byte[need];
                    byte[] f = fb[0];
                    putInt(f, 0, w);
                    putInt(f, 4, h);
                    if (rs == w * 4) {
                        b.get(f, 8, w * h * 4);
                    } else {
                        for (int y = 0; y < h; y++) {
                            b.position(y * rs);
                            b.get(f, 8 + y * w * 4, w * 4);
                        }
                    }
                    send(os, lock, 2, f, 0, need);
                    if (first[0]) {
                        first[0] = false;
                        sendText(os, lock, "ST:", "Çalışıyor");
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, new Handler(ht.getLooper()));

            String res = s.createDisplay(reader.getSurface(), w, h, dpi);
            if (!res.startsWith("OK:")) {
                sendText(os, lock, "ER:", "Ekran kurulamadı:\n" + res);
                System.exit(0);
            }
            final int id = Integer.parseInt(res.split(":")[1]);
            sendText(os, lock, "ST:", "Ekran " + id + " hazır, oyun açılıyor...");

            String comp = exec("cmd package resolve-activity --brief --user 0"
                    + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
                    + pkg + " | tail -n 1");
            if (!comp.contains("/")) {
                sendText(os, lock, "ER:", "Açılış etkinliği bulunamadı: " + comp);
                s.release();
                System.exit(0);
            }
            String o = exec("am start -S --user 0 --display " + id + " -n " + comp);
            String lo = o.toLowerCase();
            if (lo.contains("error") || lo.contains("exception")) {
                sendText(os, lock, "ER:", "Uygulama açılamadı:\n" + o);
                s.release();
                System.exit(0);
            }
            sendText(os, lock, "ST:", "Oyun başlatıldı, görüntü bekleniyor...");

            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in));
            boolean touchWarned = false;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("q")) break;
                if (line.startsWith("t ")) {
                    String[] p = line.split(" ");
                    s.touch(id, Integer.parseInt(p[1]), Float.parseFloat(p[2]),
                            Float.parseFloat(p[3]), Long.parseLong(p[4]));
                    String le = s.lastError();
                    if (!touchWarned && le.startsWith("dokunuş")) {
                        touchWarned = true;
                        sendText(os, lock, "WN:", le);
                    }
                }
            }
            s.release();
        } catch (Throwable t) {
            sendText(os, lock, "ER:", describe(t));
        }
        System.exit(0);
    }

    // ------------------------------------------------------------ self test

    private static void selfTest(String[] args) {
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
