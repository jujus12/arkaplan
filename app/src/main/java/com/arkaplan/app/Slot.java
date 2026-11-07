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
import android.view.Display;

/** One game instance running on its own secondary display. */
public class Slot {
    /**
     * true: use the system's own "simulated secondary display" (overlay display).
     * Untrusted app-owned virtual displays are not allowed to host other apps' activities.
     */
    public static final boolean OVERLAY_MODE = true;

    public static final int W = 1280, H = 720, DPI = 240;
    public static final Slot[] ALL = {new Slot(1), new Slot(2)};

    public final int index;
    public String pkg = "";
    public int userId = 0;
    public volatile int displayId = -1;
    public volatile long frames = 0;
    public volatile long lastFrameMs = 0;
    public volatile long startMs = 0;

    private volatile boolean overlayOn = false;
    private volatile VirtualDisplay vd;
    private volatile ImageReader reader;
    private HandlerThread thread;

    private Slot(int index) {
        this.index = index;
    }

    public boolean running() {
        return vd != null || overlayOn;
    }

    public static boolean anyRunning() {
        for (Slot s : ALL) if (s.running()) return true;
        return false;
    }

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

    /** Blocking: call from a background thread. */
    public synchronized String start(Context ctx) {
        if (running()) return "Zaten çalışıyor.";
        if (pkg.isEmpty()) return "Önce bir uygulama seç.";
        String err = OVERLAY_MODE ? openOverlay(ctx) : openVirtual(ctx);
        if (err != null) return err;
        startMs = SystemClock.elapsedRealtime();
        return launch();
    }

    /** System overlay display: returns an error text, or null on success. */
    private String openOverlay(Context ctx) {
        Sh.run("settings put global force_resizable_activities 1");
        StringBuilder spec = new StringBuilder();
        for (int i = 0; i < index; i++) {
            if (i > 0) spec.append(';');
            spec.append(W).append('x').append(H).append('/').append(DPI);
        }
        String cur = Sh.run("settings get global overlay_display_devices").trim();
        if (!cur.equals(spec.toString())) {
            Sh.run("settings put global overlay_display_devices '" + spec + "'");
        }
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        int id = -1;
        StringBuilder names = new StringBuilder();
        for (int t = 0; t < 20 && id < 0; t++) {
            names.setLength(0);
            for (Display d : dm.getDisplays()) {
                names.append(d.getName()).append(" (").append(d.getDisplayId()).append("); ");
                if (d.getName().contains("Overlay") && d.getName().contains("#" + index)) {
                    id = d.getDisplayId();
                }
            }
            if (id < 0) SystemClock.sleep(500);
        }
        if (id < 0) {
            return "Sistem ikinci ekranı bulunamadı. Görünen ekranlar: " + names;
        }
        displayId = id;
        overlayOn = true;
        frames = 0;
        lastFrameMs = 0;
        return null;
    }

    /** App-owned virtual display (kept for reference; other apps are usually refused). */
    private String openVirtual(Context ctx) {
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
            return null;
        } catch (Throwable t) {
            stop();
            return "Sanal ekran hatası: " + t;
        }
    }

    private String launch() {
        if (userId != 0) Sh.run("am start-user " + userId);
        // -S: restart the game so it really opens on the target display
        // instead of just bringing an existing window to front.
        String out = Sh.run("am start -S --user " + userId + " --display " + displayId
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                + pkg);
        String lower = out.toLowerCase();
        if (lower.contains("error") || lower.contains("exception")
                || lower.contains("does not exist")) {
            StringBuilder key = new StringBuilder();
            int n = 0;
            for (String line : out.split("\n")) {
                String l = line.toLowerCase();
                if (n < 3 && (l.contains("exception") || l.contains("error")
                        || l.contains("denial") || l.contains("not "))
                        && !l.trim().startsWith("at ")) {
                    key.append(line.trim()).append("\n");
                    n++;
                }
            }
            stop();
            return "Oyun açılamadı (kullanıcı " + userId + ", paket " + pkg + ").\n"
                    + "ASIL HATA: " + (key.length() == 0 ? "(bulunamadı)" : key) + "\n"
                    + "Tam çıktı: " + out;
        }
        return "Ekran " + displayId + " üzerinde başlatıldı.\n" + out;
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
        boolean wasOverlay = overlayOn;
        overlayOn = false;
        displayId = -1;
        if (wasOverlay && !anyRunning()) {
            Sh.run("settings delete global overlay_display_devices");
        }
    }
}
