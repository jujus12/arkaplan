package com.arkaplan.app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

/** One game instance running on its own invisible virtual display. */
public class Slot {
    public static final int W = 1280, H = 720, DPI = 240;
    public static final Slot[] ALL = {new Slot(1), new Slot(2)};

    public final int index;
    public String pkg = "";
    public int userId = 0;
    public volatile int displayId = -1;
    public volatile long frames = 0;
    public volatile long lastFrameMs = 0;
    public volatile long startMs = 0;

    private volatile VirtualDisplay vd;
    private volatile ImageReader reader;
    private HandlerThread thread;

    /** Show the display on a visible surface; null goes back to the hidden one. */
    public void setViewSurface(android.view.Surface s) {
        try {
            VirtualDisplay d = vd;
            ImageReader r = reader;
            if (d == null || r == null) return;
            d.setSurface(s != null ? s : r.getSurface());
        } catch (Throwable ignored) {
        }
    }

    private Slot(int index) {
        this.index = index;
    }

    public boolean running() {
        return vd != null;
    }

    public static boolean anyRunning() {
        for (Slot s : ALL) if (s.running()) return true;
        return false;
    }

    /** Blocking: call from a background thread. */
    public synchronized String start(Context ctx) {
        if (vd != null) return "Zaten çalışıyor.";
        if (pkg.isEmpty()) return "Önce bir uygulama seç.";
        try {
            frames = 0;
            lastFrameMs = 0;
            thread = new HandlerThread("arkaplan-" + index);
            thread.start();
            reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img != null) {
                        frames++;
                        lastFrameMs = SystemClock.elapsedRealtime();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, new Handler(thread.getLooper()));

            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            vd = dm.createVirtualDisplay("arkaplan-" + index, W, H, DPI, reader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (vd == null) {
                stop();
                return "Sanal ekran oluşturulamadı.";
            }
            displayId = vd.getDisplay().getDisplayId();
        } catch (Throwable t) {
            stop();
            return "Sanal ekran hatası: " + t;
        }
        startMs = SystemClock.elapsedRealtime();

        String comp = Sh.run("cmd package resolve-activity --brief --user " + userId
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
                + pkg + " | tail -n 1");
        if (!comp.contains("/")) {
            comp = Sh.run("cmd package resolve-activity --brief --user " + userId
                    + " " + pkg + " | tail -n 1");
        }
        if (!comp.contains("/")) {
            stop();
            return "Oyun bu kullanıcıda bulunamadı (kullanıcı " + userId + "): " + comp;
        }
        String out = Sh.run("am start --user " + userId + " --display " + displayId
                + " -n " + comp);
        return "Sanal ekran " + displayId + " açıldı.\n" + out;
    }

    /** Blocking: call from a background thread. */
    public synchronized void stop() {
        try {
            if (vd != null) vd.release();
        } catch (Throwable ignored) {
        }
        vd = null;
        try {
            if (reader != null) reader.close();
        } catch (Throwable ignored) {
        }
        reader = null;
        if (thread != null) thread.quitSafely();
        thread = null;
        displayId = -1;
    }
}
