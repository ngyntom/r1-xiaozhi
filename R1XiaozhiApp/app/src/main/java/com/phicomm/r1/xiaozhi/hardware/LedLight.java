package com.phicomm.r1.xiaozhi.hardware;

import android.util.Log;

/**
 * Wrapper for R1 LED hardware control.
 *
 * The actual native call is delegated to
 * com.phicomm.speaker.player.light.LedLight instead of being declared here.
 * JNI resolves a `native` method by the fully qualified class name baked
 * into the mangled symbol, and the prebuilt libledLight-jni.so in the R1
 * firmware was built for that exact class (the stock r1-helper app) - a
 * native method declared under this app's own package always throws
 * UnsatisfiedLinkError at call time no matter how it's structured, even
 * though System.loadLibrary() itself succeeds either way.
 *
 * Requirements:
 * - Root access
 * - SELinux permissive mode (setenforce 0)
 * - Native library libledLight-jni.so (pre-installed in R1 firmware)
 */
public class LedLight {
    private static final String TAG = "LedLight";

    /**
     * Set LED color with maximum brightness.
     *
     * @param color RGB color value (0xRRGGBB format)
     *              Example: 0xFF0000 = red, 0x00FF00 = green, 0x0000FF = blue
     */
    public static void setColor(int color) {
        setColor(32767L, color);  // 32767 = 0x7FFF = max brightness
    }

    /**
     * Set LED color with custom brightness.
     *
     * @param brightness Brightness level (0-32767, where 32767 is maximum)
     * @param color RGB color value (0xRRGGBB format)
     */
    public static void setColor(long brightness, int color) {
        if (!com.phicomm.speaker.player.light.LedLight.loaded) {
            Log.w(TAG, "Cannot set LED color - native library not loaded");
            return;
        }
        try {
            com.phicomm.speaker.player.light.LedLight.set_color(brightness, color);
        } catch (UnsatisfiedLinkError e) {
            com.phicomm.speaker.player.light.LedLight.loaded = false;
            Log.w(TAG, "LED native symbol not found - disabling LED control: " + e.getMessage());
        }
    }

    public static boolean isLoaded() {
        return com.phicomm.speaker.player.light.LedLight.loaded;
    }
}
