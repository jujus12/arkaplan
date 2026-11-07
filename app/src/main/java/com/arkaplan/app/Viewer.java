package com.arkaplan.app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Floating window that shows a slot's virtual display and forwards taps/swipes to it. */
public class Viewer {
    private static final Viewer[] ALL = {null, null};
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();

    private final Context ctx;
    private final Slot slot;
    private WindowManager wm;
    private LinearLayout root;
    private WindowManager.LayoutParams lp;
    private float downX, downY;
    private long downT;

    private Viewer(Context c, Slot s) {
        ctx = c;
        slot = s;
    }

    /** Call on the UI thread. */
    public static void toggle(Context c, Slot s) {
        int i = s.index - 1;
        if (ALL[i] != null) {
            close(s);
            return;
        }
        Viewer v = new Viewer(c, s);
        ALL[i] = v;
        v.show();
    }

    /** Call on the UI thread. */
    public static void close(Slot s) {
        int i = s.index - 1;
        Viewer v = ALL[i];
        ALL[i] = null;
        if (v != null) v.hide();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private void show() {
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        final int w = dp(420);
        final int sh = w * Slot.H / Slot.W;

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF202020);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("  Oyun " + slot.index + "  (buradan sürükle)");
        title.setTextColor(0xFFFFFFFF);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));

        Button close = new Button(ctx);
        close.setText("✕");
        close.setMinHeight(0);
        close.setMinimumHeight(0);
        close.setPadding(0, 0, 0, 0);
        close.setOnClickListener(v -> close(slot));
        bar.addView(close, new LinearLayout.LayoutParams(dp(56), dp(36)));
        root.addView(bar);

        SurfaceView sv = new SurfaceView(ctx);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                slot.setViewSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                slot.setViewSurface(null);
            }
        });
        sv.setOnTouchListener(this::onTouch);
        root.addView(sv, new LinearLayout.LayoutParams(w, sh));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(20) + (slot.index - 1) * dp(40);
        lp.y = dp(40) + (slot.index - 1) * dp(40);

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

    private void hide() {
        try {
            if (root != null) wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
    }

    private boolean onTouch(View v, MotionEvent e) {
        float sx = Slot.W / (float) Math.max(1, v.getWidth());
        float sy = Slot.H / (float) Math.max(1, v.getHeight());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downT = e.getEventTime();
                return true;
            case MotionEvent.ACTION_UP: {
                int id = slot.displayId;
                if (id < 0) return true;
                int x1 = (int) (downX * sx), y1 = (int) (downY * sy);
                int x2 = (int) (e.getX() * sx), y2 = (int) (e.getY() * sy);
                long dt = e.getEventTime() - downT;
                double dist = Math.hypot(e.getX() - downX, e.getY() - downY);
                final String cmd;
                if (dist < dp(8) && dt < 350) {
                    cmd = "input -d " + id + " tap " + x1 + " " + y1;
                } else {
                    cmd = "input -d " + id + " swipe " + x1 + " " + y1 + " "
                            + x2 + " " + y2 + " " + Math.max(80, dt);
                }
                EX.execute(() -> Sh.run(cmd));
                return true;
            }
            default:
                return true;
        }
    }
}
