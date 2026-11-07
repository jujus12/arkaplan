package com.arkaplan.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/** Runs a shell command with Shizuku (adb-shell) privileges. */
public final class Sh {
    private Sh() {}

    public static String run(String cmd) {
        try {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null,
                    new Object[]{new String[]{"sh", "-c", cmd}, null, null});
            final String[] err = {""};
            Thread t = new Thread(() -> err[0] = read(p.getErrorStream()));
            t.start();
            String out = read(p.getInputStream());
            p.waitFor();
            t.join(2000);
            return (out + err[0]).trim();
        } catch (Throwable t) {
            return "HATA: " + t;
        }
    }

    /** Starts a long-running shell-privileged process and returns it (caller manages streams). */
    public static Process spawn(String cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null,
                new Object[]{new String[]{"sh", "-c", cmd}, null, null});
    }

    private static String read(InputStream in) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
