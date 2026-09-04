package com.phicomm.r1.xiaozhi.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.support.v4.content.ContextCompat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.phicomm.r1.xiaozhi.config.XiaozhiConfig;
import com.phicomm.r1.xiaozhi.core.ListeningMode;
import com.phicomm.r1.xiaozhi.core.XiaozhiCore;

import org.concentus.OpusApplication;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;

import java.util.Arrays;

/**
 * Service thu âm và phát hiện wake word liên tục
 * Khi phát hiện wake word, bắt đầu ghi âm đầy đủ và gửi đến Xiaozhi
 *
 * FIX: Added permission checks to prevent SecurityException crash
 */
public class VoiceRecognitionService extends Service {
    
    private static final String TAG = "VoiceRecognition";
    private static final String CHANNEL_ID = "voice_recognition_channel";
    private static final int NOTIFICATION_ID = 1;

    // Action intent commands - dùng để điều khiển từ Web UI / HTTPServerService
    public static final String ACTION_SET_LISTENING = "com.phicomm.r1.xiaozhi.VOICE_LISTENING";
    public static final String ACTION_UPDATE_WAKE_WORD = "com.phicomm.r1.xiaozhi.VOICE_UPDATE_WAKE_WORD";
    public static final String ACTION_PUSH_TO_TALK = "com.phicomm.r1.xiaozhi.PUSH_TO_TALK";
    public static final String EXTRA_LISTENING = "listening";

    // Audio configuration - 960 samples @ 16kHz = 60ms, matching the
    // audio_params.frame_duration declared in the WebSocket hello handshake
    // (XiaozhiConnectionService.sendHelloMessage()) so every AudioRecord
    // read is exactly one Opus frame, no partial-frame buffering needed.
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SIZE = 960;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_FACTOR = 2;
    // ~10 seconds of talking per turn (10s / 60ms per frame)
    private static final int MAX_FRAMES_PER_TURN = 166;

    // Recording state
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private XiaozhiConfig config;

    // Wake word detection
    private boolean isListeningForWakeWord = false;
    private boolean isRecordingCommand = false;
    private OpusEncoder opusEncoder;
    private int framesSentThisTurn = 0;

    // Energy-based Voice Activity Detection
    private static final double ENERGY_THRESHOLD = 500.0;
    private static final int SILENCE_FRAMES = 20; // ~0.4 seconds at 50fps
    private int silenceCounter = 0;
    
    private VoiceCallback callback;
    
    public interface VoiceCallback {
        void onWakeWordDetected();
        void onRecordingStarted();
        void onRecordingCompleted(byte[] audioData);
        void onVoiceActivityDetected();
        void onError(String error);
    }
    
    private final IBinder binder = new LocalBinder();
    
    public class LocalBinder extends Binder {
        public VoiceRecognitionService getService() {
            return VoiceRecognitionService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        config = new XiaozhiConfig(this);

        // Đăng ký với XiaozhiCore để Web UI có thể truy cập trạng thái
        XiaozhiCore.getInstance().setVoiceService(this);

        Log.d(TAG, "VoiceRecognitionService created");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Xử lý lệnh điều khiển từ Web UI / HTTPServerService
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();

            if (ACTION_SET_LISTENING.equals(action)) {
                boolean listening = intent.getBooleanExtra(EXTRA_LISTENING, true);
                Log.i(TAG, "Set listening (from Web UI): " + listening);
                setListening(listening);
                // Cập nhật lại notification với trạng thái mới
                updateNotification();
                return START_STICKY;
            }

            if (ACTION_UPDATE_WAKE_WORD.equals(action)) {
                Log.i(TAG, "Wake word updated - refreshing notification");
                if (config != null) {
                    config = new XiaozhiConfig(this);
                    Log.i(TAG, "New wake word: " + config.getWakeWord());
                }
                updateNotification();
                return START_STICKY;
            }

            if (ACTION_PUSH_TO_TALK.equals(action)) {
                startPushToTalk();
                return START_STICKY;
            }
        }

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        // FIX #2: Check permission before starting recording
        if (checkRecordAudioPermission()) {
            startRecording();
        } else {
            Log.e(TAG, "=== RECORD_AUDIO PERMISSION DENIED ===");
            Log.e(TAG, "Cannot start recording without permission!");
            Log.e(TAG, "Service will run but recording is disabled.");

            if (callback != null) {
                callback.onError("Khong co quyen ghi am");
            }
        }

        return START_STICKY;
    }

    /**
     * Cập nhật lại foreground notification (hiển thị wake word hiện tại)
     */
    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, createNotification());
        }
    }

    /**
     * FIX #2: Check RECORD_AUDIO permission before accessing microphone
     * Use ContextCompat for API 22 compatibility
     */
    private boolean checkRecordAudioPermission() {
        boolean hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;

        Log.i(TAG, "RECORD_AUDIO permission: " + hasPermission);
        return hasPermission;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    public void setCallback(VoiceCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Tạo notification channel cho Android O+
     * Không cần cho API 22
     */
    private void createNotificationChannel() {
        // NotificationChannel chỉ có từ API 26+
        // API 22 không cần tạo channel
    }
    
    /**
     * Tạo notification cho foreground service
     */
    private Notification createNotification() {
        // API 22 chỉ cần Builder đơn giản
        Notification.Builder builder = new Notification.Builder(this);
        
        return builder
            .setContentTitle("Xiaozhi Voice Assistant")
            .setContentText("Đang lắng nghe: " + config.getWakeWord())
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build();
    }
    
    /**
     * Bắt đầu thu âm liên tục
     * FIX #2: Enhanced error handling and permission checks
     */
    private void startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return;
        }

        // FIX #2: Double-check permission before creating AudioRecord
        if (!checkRecordAudioPermission()) {
            Log.e(TAG, "Cannot start recording: No RECORD_AUDIO permission");
            if (callback != null) {
                callback.onError("Khong co quyen ghi am");
            }
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_FACTOR;

        Log.i(TAG, "=== STARTING AUDIO RECORDING ===");
        Log.i(TAG, "Sample rate: " + SAMPLE_RATE);
        Log.i(TAG, "Buffer size: " + bufferSize);

        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "=== AUDIORECORD INITIALIZATION FAILED ===");
                Log.e(TAG, "State: " + audioRecord.getState());
                Log.e(TAG, "Expected: " + AudioRecord.STATE_INITIALIZED);

                if (callback != null) {
                    callback.onError("Khong the khoi tao microphone");
                }

                // Clean up
                if (audioRecord != null) {
                    audioRecord.release();
                    audioRecord = null;
                }
                return;
            }

            isRecording = true;
            recordingThread = new Thread(new RecordingRunnable());
            recordingThread.start();

            Log.i(TAG, "=== RECORDING STARTED SUCCESSFULLY ===");
            Log.i(TAG, "Wake word: " + config.getWakeWord());
            Log.i(TAG, "Energy threshold: " + ENERGY_THRESHOLD);

        } catch (SecurityException e) {
            Log.e(TAG, "=== SECURITY EXCEPTION ===", e);
            Log.e(TAG, "No RECORD_AUDIO permission!");

            if (callback != null) {
                callback.onError("Khong co quyen ghi am");
            }

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "=== ILLEGAL ARGUMENT EXCEPTION ===", e);
            Log.e(TAG, "Invalid AudioRecord parameters!");

            if (callback != null) {
                callback.onError("Cau hinh microphone khong hop le");
            }

        } catch (Exception e) {
            Log.e(TAG, "=== UNEXPECTED EXCEPTION ===", e);

            if (callback != null) {
                callback.onError("Loi khong xac dinh: " + e.getMessage());
            }
        }
    }
    
    /**
     * Recording loop - chạy trong background thread
     */
    private class RecordingRunnable implements Runnable {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            
            short[] buffer = new short[FRAME_SIZE];
            audioRecord.startRecording();
            
            Log.d(TAG, "Recording loop started");
            
            while (isRecording) {
                int shortsRead = audioRecord.read(buffer, 0, buffer.length);
                
                if (shortsRead > 0) {
                    processAudioBuffer(buffer, shortsRead);
                } else {
                    Log.w(TAG, "AudioRecord read error: " + shortsRead);
                }
            }
            
            Log.d(TAG, "Recording loop ended");
        }
    }
    
    /**
     * Xử lý audio buffer
     */
    private void processAudioBuffer(short[] buffer, int length) {
        if (isListeningForWakeWord) {
            // Mode 1: Phát hiện wake word
            boolean wakeWordDetected = detectWakeWord(buffer, length);
            
            if (wakeWordDetected) {
                onWakeWordDetected();
            }
        } else if (isRecordingCommand) {
            // Mode 2: Ghi âm command sau khi phát hiện wake word
            recordCommandAudio(buffer, length);
        }
    }
    
    /**
     * Phát hiện wake word đơn giản dựa trên energy và pattern
     * TODO: Tích hợp thư viện wake word detection chuyên dụng như Porcupine
     */
    private boolean detectWakeWord(short[] buffer, int length) {
        double energy = calculateEnergy(buffer, length);
        
        // Simple energy-based detection
        // Trong production nên dùng model ML như Porcupine, Snowboy
        if (energy > ENERGY_THRESHOLD * 3) {
            Log.d(TAG, "High energy detected, possible wake word: " + energy);
            return true;
        }
        
        return false;
    }
    
    /**
     * Tính năng lượng audio để phát hiện voice activity
     */
    private double calculateEnergy(short[] buffer, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / length);
    }
    
    /**
     * Manual trigger from the Web UI "Nhấn để nói" (push-to-talk) button -
     * bypasses the energy-threshold wake word entirely and starts a real
     * listen session directly. This is the primary/reliable way to talk to
     * the assistant until real wake word detection is implemented.
     */
    public void startPushToTalk() {
        if (isRecordingCommand) {
            Log.w(TAG, "Push-to-talk requested but already recording a command");
            return;
        }

        if (!isRecording) {
            if (!checkRecordAudioPermission()) {
                Log.e(TAG, "Cannot start push-to-talk: no RECORD_AUDIO permission");
                if (callback != null) {
                    callback.onError("Khong co quyen ghi am");
                }
                return;
            }
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());
            startRecording();
        }

        beginRecordingSession();
    }

    /**
     * Xử lý khi phát hiện wake word (energy-threshold path)
     */
    private void onWakeWordDetected() {
        Log.d(TAG, "Wake word detected!");
        beginRecordingSession();
    }

    /**
     * Shared setup for both the energy-threshold wake word path and the
     * push-to-talk button: open a "listen" session on the server and start
     * streaming Opus frames.
     */
    private void beginRecordingSession() {
        isListeningForWakeWord = false;
        isRecordingCommand = true;
        silenceCounter = 0;
        framesSentThisTurn = 0;

        if (opusEncoder == null) {
            try {
                opusEncoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            } catch (OpusException e) {
                Log.e(TAG, "Failed to create OpusEncoder", e);
                isRecordingCommand = false;
                isListeningForWakeWord = true;
                if (callback != null) {
                    callback.onError("Khong the khoi tao Opus encoder: " + e.getMessage());
                }
                return;
            }
        }

        XiaozhiConnectionService cs = XiaozhiCore.getInstance().getConnectionService();
        if (cs != null) {
            cs.sendStartListening(ListeningMode.MANUAL);
        } else {
            Log.w(TAG, "ConnectionService not bound - cannot start listen session");
        }

        if (callback != null) {
            callback.onWakeWordDetected();
            callback.onRecordingStarted();
        }

        // Notify LED service
        Intent ledIntent = new Intent(this, LEDControlService.class);
        ledIntent.setAction(LEDControlService.ACTION_SET_LISTENING);
        startService(ledIntent);
    }

    /**
     * Ghi âm command: encode mỗi frame PCM thành Opus và gửi ngay lập tức
     * dưới dạng binary WebSocket frame (giao thức thật là streaming liên
     * tục trong lúc "listen", không phải gửi 1 blob lớn sau khi ghi xong).
     */
    private void recordCommandAudio(short[] buffer, int length) {
        double energy = calculateEnergy(buffer, length);

        XiaozhiConnectionService cs = XiaozhiCore.getInstance().getConnectionService();
        if (cs != null && opusEncoder != null) {
            try {
                byte[] opusOut = new byte[4000]; // safety margin above any real Opus frame size
                int bytesWritten = opusEncoder.encode(buffer, 0, length, opusOut, 0, opusOut.length);
                if (bytesWritten > 0) {
                    cs.sendAudioFrame(Arrays.copyOf(opusOut, bytesWritten));
                }
            } catch (OpusException e) {
                Log.e(TAG, "Opus encode failed: " + e.getMessage());
            }
        }
        framesSentThisTurn++;

        // Phát hiện kết thúc câu lệnh (silence detection)
        if (energy < ENERGY_THRESHOLD) {
            silenceCounter++;

            if (silenceCounter >= SILENCE_FRAMES) {
                onCommandRecordingCompleted();
                return; // FIX: Return immediately to prevent double-call
            }
        } else {
            silenceCounter = 0;

            if (callback != null) {
                callback.onVoiceActivityDetected();
            }
        }

        if (framesSentThisTurn > MAX_FRAMES_PER_TURN) {
            Log.w(TAG, "Recording too long, force stopping");
            onCommandRecordingCompleted();
        }
    }

    /**
     * Hoàn thành ghi âm command
     * FIX: Added null check and prevent double-call
     */
    private void onCommandRecordingCompleted() {
        // FIX: Prevent double-call - check if already completed
        if (!isRecordingCommand) {
            Log.w(TAG, "onCommandRecordingCompleted called but not recording, ignoring");
            return;
        }

        Log.d(TAG, "Command recording completed (" + framesSentThisTurn + " frames sent)");

        // FIX: Set flags FIRST to prevent re-entry
        isRecordingCommand = false;
        isListeningForWakeWord = true;
        framesSentThisTurn = 0;

        XiaozhiConnectionService cs = XiaozhiCore.getInstance().getConnectionService();
        if (cs != null) {
            cs.sendStopListening();
        }

        if (callback != null) {
            callback.onRecordingCompleted(new byte[0]);
        }

        // Reset LED
        Intent ledIntent = new Intent(this, LEDControlService.class);
        ledIntent.setAction(LEDControlService.ACTION_SET_IDLE);
        startService(ledIntent);
    }
    
    /**
     * Dừng thu âm
     */
    public void stopRecording() {
        isRecording = false;
        
        if (recordingThread != null) {
            try {
                recordingThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping recording thread", e);
            }
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing AudioRecord", e);
            }
            audioRecord = null;
        }
        
        Log.d(TAG, "Recording stopped");
    }
    
    /**
     * Pause/Resume listening
     */
    public void setListening(boolean listening) {
        isListeningForWakeWord = listening;
        Log.d(TAG, "Listening: " + listening);

        // Nếu bật listening nhưng chưa recording (service vừa start qua action),
        // thì bắt đầu recording lại
        if (listening && !isRecording) {
            Log.i(TAG, "Listening enabled but not recording - starting recording");
            if (checkRecordAudioPermission()) {
                startRecording();
            } else {
                Log.e(TAG, "Cannot start recording on enable - no RECORD_AUDIO permission");
            }
        }
    }
    
    public boolean isListening() {
        return isListeningForWakeWord;
    }
    
    @Override
    public void onDestroy() {
        stopRecording();

        // Hủy đăng ký khỏi XiaozhiCore
        XiaozhiCore.getInstance().setVoiceService(null);

        super.onDestroy();
        Log.d(TAG, "VoiceRecognitionService destroyed");
    }
}