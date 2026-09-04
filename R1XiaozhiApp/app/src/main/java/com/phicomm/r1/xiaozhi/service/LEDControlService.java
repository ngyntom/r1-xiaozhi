package com.phicomm.r1.xiaozhi.service;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.phicomm.r1.xiaozhi.config.XiaozhiConfig;
import com.phicomm.r1.xiaozhi.hardware.LedLight;

/**
 * Service điều khiển LED strip của Phicomm R1
 * Hiển thị trạng thái hoạt động qua màu sắc và animation
 *
 * LED is driven via EchoServiceBridge (see LedLight) - a local WebSocket
 * call asking the stock, privileged EchoService to run the LED shell
 * command on our behalf, since this app's own process is confined by
 * SELinux and can't touch the LED device file directly, root or not.
 */
public class LEDControlService extends Service {

    private static final String TAG = "LEDControl";
    
    // Actions
    public static final String ACTION_SET_IDLE = "com.phicomm.r1.xiaozhi.LED_IDLE";
    public static final String ACTION_SET_LISTENING = "com.phicomm.r1.xiaozhi.LED_LISTENING";
    public static final String ACTION_SET_THINKING = "com.phicomm.r1.xiaozhi.LED_THINKING";
    public static final String ACTION_SET_SPEAKING = "com.phicomm.r1.xiaozhi.LED_SPEAKING";
    public static final String ACTION_SET_ERROR = "com.phicomm.r1.xiaozhi.LED_ERROR";
    public static final String ACTION_SET_COLOR = "com.phicomm.r1.xiaozhi.LED_COLOR";
    public static final String ACTION_STOP_ANIMATION = "com.phicomm.r1.xiaozhi.LED_STOP";
    
    // LED States
    private static final int STATE_IDLE = 0;
    private static final int STATE_LISTENING = 1;
    private static final int STATE_THINKING = 2;
    private static final int STATE_SPEAKING = 3;
    private static final int STATE_ERROR = 4;
    
    private int currentState = STATE_IDLE;
    private boolean isAnimating = false;
    private Handler animationHandler;
    private Runnable animationRunnable;
    private XiaozhiConfig config;

    private final IBinder binder = new LocalBinder();
    
    public class LocalBinder extends Binder {
        public LEDControlService getService() {
            return LEDControlService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        config = new XiaozhiConfig(this);
        animationHandler = new Handler();
        Log.i(TAG, "LEDControlService created - LED control via EchoServiceBridge (no root needed)");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            
            switch (action) {
                case ACTION_SET_IDLE:
                    setIdleState();
                    break;
                    
                case ACTION_SET_LISTENING:
                    setListeningState();
                    break;
                    
                case ACTION_SET_THINKING:
                    setThinkingState();
                    break;
                    
                case ACTION_SET_SPEAKING:
                    setSpeakingState();
                    break;
                    
                case ACTION_SET_ERROR:
                    setErrorState();
                    break;
                    
                case ACTION_SET_COLOR:
                    int color = intent.getIntExtra("color", Color.WHITE);
                    setLEDColor(color);
                    break;
                    
                case ACTION_STOP_ANIMATION:
                    stopAnimation();
                    break;
            }
        }
        
        return START_NOT_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    /**
     * Set LED color via EchoServiceBridge (no root/SELinux permissive
     * needed - EchoService itself runs the privileged write).
     *
     * @param color RGB color value (0xRRGGBB format)
     */
    public void setLEDColor(int color) {
        if (!config.isLedEnabled()) {
            return;
        }

        LedLight.setColor(color);

        // Only log in debug mode to avoid log spam
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "LED color set to: " + String.format("%06x", color & 0xFFFFFF));
        }
    }
    
    /**
     * Idle state - màu xanh dương nhạt
     */
    public void setIdleState() {
        stopAnimation();
        currentState = STATE_IDLE;
        setLEDColor(0x0066CC); // Xanh dương nhạt
        Log.d(TAG, "State: IDLE");
    }
    
    /**
     * Listening state - xoay tròn màu xanh lá
     */
    public void setListeningState() {
        stopAnimation();
        currentState = STATE_LISTENING;
        startRotatingAnimation(0x00FF00, 50); // Xanh lá
        Log.d(TAG, "State: LISTENING");
    }
    
    /**
     * Thinking state - pulse màu trắng
     */
    public void setThinkingState() {
        stopAnimation();
        currentState = STATE_THINKING;
        startPulseAnimation(0xFFFFFF, 200); // Trắng
        Log.d(TAG, "State: THINKING");
    }
    
    /**
     * Speaking state - màu xanh cyan
     */
    public void setSpeakingState() {
        stopAnimation();
        currentState = STATE_SPEAKING;
        setLEDColor(0x00FFFF); // Cyan
        Log.d(TAG, "State: SPEAKING");
    }
    
    /**
     * Error state - nhấp nháy đỏ
     */
    public void setErrorState() {
        stopAnimation();
        currentState = STATE_ERROR;
        startBlinkAnimation(0xFF0000, 300); // Đỏ
        Log.d(TAG, "State: ERROR");
    }
    
    /**
     * Animation: Rotating colors (cho listening)
     */
    private void startRotatingAnimation(final int baseColor, final int delayMs) {
        isAnimating = true;
        
        animationRunnable = new Runnable() {
            private int hue = 0;
            
            @Override
            public void run() {
                if (!isAnimating) return;
                
                float[] hsv = new float[3];
                Color.colorToHSV(baseColor, hsv);
                hsv[0] = hue;
                
                int color = Color.HSVToColor(hsv);
                setLEDColor(color);
                
                hue = (hue + 10) % 360;
                animationHandler.postDelayed(this, delayMs);
            }
        };
        
        animationHandler.post(animationRunnable);
    }
    
    /**
     * Animation: Pulse (cho thinking)
     */
    private void startPulseAnimation(final int color, final int delayMs) {
        isAnimating = true;
        
        animationRunnable = new Runnable() {
            private boolean increasing = true;
            private int brightness = 0;
            
            @Override
            public void run() {
                if (!isAnimating) return;
                
                // Adjust brightness
                if (increasing) {
                    brightness += 20;
                    if (brightness >= 255) {
                        brightness = 255;
                        increasing = false;
                    }
                } else {
                    brightness -= 20;
                    if (brightness <= 0) {
                        brightness = 0;
                        increasing = true;
                    }
                }
                
                // Apply brightness to color
                int adjustedColor = adjustColorBrightness(color, brightness / 255.0f);
                setLEDColor(adjustedColor);
                
                animationHandler.postDelayed(this, delayMs);
            }
        };
        
        animationHandler.post(animationRunnable);
    }
    
    /**
     * Animation: Blink (cho error)
     */
    private void startBlinkAnimation(final int color, final int delayMs) {
        isAnimating = true;
        
        animationRunnable = new Runnable() {
            private boolean isOn = true;
            
            @Override
            public void run() {
                if (!isAnimating) return;
                
                if (isOn) {
                    setLEDColor(color);
                } else {
                    setLEDColor(0x000000); // Off
                }
                
                isOn = !isOn;
                animationHandler.postDelayed(this, delayMs);
            }
        };
        
        animationHandler.post(animationRunnable);
    }
    
    /**
     * Stop current animation
     */
    private void stopAnimation() {
        if (animationRunnable != null) {
            animationHandler.removeCallbacks(animationRunnable);
            animationRunnable = null;
        }
        isAnimating = false;
    }
    
    /**
     * Adjust color brightness
     */
    private int adjustColorBrightness(int color, float factor) {
        int r = (int) (Color.red(color) * factor);
        int g = (int) (Color.green(color) * factor);
        int b = (int) (Color.blue(color) * factor);
        
        return Color.rgb(
            Math.min(255, Math.max(0, r)),
            Math.min(255, Math.max(0, g)),
            Math.min(255, Math.max(0, b))
        );
    }
    
    public int getCurrentState() {
        return currentState;
    }
    
    @Override
    public void onDestroy() {
        stopAnimation();
        setLEDColor(0x000000); // Turn off
        super.onDestroy();
        Log.d(TAG, "LEDControlService destroyed");
    }
}