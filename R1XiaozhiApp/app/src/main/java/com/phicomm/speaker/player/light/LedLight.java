package com.phicomm.speaker.player.light;

import android.util.Log;

/**
 * Native JNI bridge for the R1's LED hardware, kept at this exact package
 * path on purpose: the prebuilt libledLight-jni.so that ships in the R1
 * firmware was built for the stock r1-helper app
 * (com.phicomm.speaker.player.light.LedLight), and JNI resolves native
 * methods by the *fully qualified* declaring class name baked into the
 * mangled symbol name. Declaring set_color() anywhere else (e.g. under our
 * own com.phicomm.r1.xiaozhi.* package, as was tried first) throws
 * UnsatisfiedLinkError at call time even though System.loadLibrary()
 * itself succeeds - see com.phicomm.r1.xiaozhi.hardware.LedLight, which
 * now delegates here instead of declaring the native method itself.
 */
public class LedLight {
    private static final String TAG = "LedLightBridge";

    public static boolean loaded = false;

    public static native void set_color(long brightness, int color);

    static {
        try {
            System.loadLibrary("ledLight-jni");
            loaded = true;
            Log.i(TAG, "R1 native LED library loaded (bridge package)");
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
            Log.w(TAG, "R1 LED library not found: " + e.getMessage());
        }
    }
}
