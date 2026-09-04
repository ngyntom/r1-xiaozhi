package com.phicomm.r1.xiaozhi.hardware;

/**
 * LED hardware control.
 *
 * Controlled via EchoServiceBridge (a WebSocket call to the stock
 * EchoService, which runs as android.uid.system) rather than a direct JNI/
 * sysfs write - this app's own process is confined by SELinux
 * (u:r:untrusted_app:s0) and is denied write access to the LED sysfs node
 * no matter how the write is attempted (confirmed via dmesg avc denial),
 * so only a privileged process - EchoService - can actually flip it.
 *
 * Command format reverse-engineered from the official web control panel's
 * script (r1.wxfsq.com/js/r1_control.min.js): "lights_test set <flags>
 * <RRGGBB or 0>".
 */
public class LedLight {

    private static final String BRIGHTNESS_FLAGS = "7fffff8000";

    /**
     * Set LED color with maximum brightness.
     *
     * @param color RGB color value (0xRRGGBB format)
     *              Example: 0xFF0000 = red, 0x00FF00 = green, 0x0000FF = blue
     */
    public static void setColor(int color) {
        String hex = String.format("%06x", color & 0xFFFFFF);
        EchoServiceBridge.sendShellCommand("lights_test set " + BRIGHTNESS_FLAGS + " " + hex);
    }

    /**
     * Set LED color - brightness parameter kept for API compatibility with
     * callers ported from the old JNI wrapper, but EchoService's
     * "lights_test" command doesn't take a separate brightness value the
     * way the native call did.
     */
    public static void setColor(long brightness, int color) {
        setColor(color);
    }

    /**
     * Turn the LED off.
     */
    public static void turnOff() {
        EchoServiceBridge.sendShellCommand("lights_test set " + BRIGHTNESS_FLAGS + " 0");
    }
}
