package com.phicomm.r1.xiaozhi.hardware;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;

/**
 * Bridge to the stock R1 firmware's EchoService (com.phicomm.speaker.player,
 * package name on disk "EchoService" - matches the "please install
 * new_EchoService" message its HTTP port shows). It runs as
 * android.uid.system, and accepts arbitrary shell commands over a local,
 * UNAUTHENTICATED WebSocket on port 8080 - reverse-engineered from the
 * official Phicomm web control panel's script
 * (http://r1.wxfsq.com/js/r1_control.min.js), which drives LED and other
 * hardware features this same way instead of touching device files
 * directly.
 *
 * This is how the LED gets controlled WITHOUT rooting this app: EchoService
 * itself has the privileges (and is already whitelisted by the device's
 * SELinux policy) to write to /sys/class/leds/..., our app just has to ask
 * it to run the command - confirmed working live (verified against the real
 * hardware, not just no Java exception).
 *
 * NOTE: since this channel accepts literally any shell command with no
 * auth check, it is effectively a standing local-network backdoor baked
 * into the stock firmware - worth being aware of, not something introduced
 * by this app.
 */
public class EchoServiceBridge {
    private static final String TAG = "EchoServiceBridge";
    private static final String WS_URL = "ws://127.0.0.1:8080";

    private static WebSocketClient client;
    private static boolean connecting = false;

    /**
     * Send a shell command to be executed by EchoService (which runs as
     * android.uid.system). Fire-and-forget - the reply (if any) is only
     * logged, not surfaced to the caller.
     */
    public static synchronized void sendShellCommand(String shellCmd) {
        ensureConnected();
        if (client != null && client.isOpen()) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "shell");
                msg.put("shell", shellCmd);
                client.send(msg.toString());
            } catch (Exception e) {
                Log.w(TAG, "Failed to send shell command: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "EchoService WS not connected yet, dropping command: " + shellCmd);
        }
    }

    private static synchronized void ensureConnected() {
        if (client != null && client.isOpen()) {
            return;
        }
        if (connecting) {
            return;
        }
        connecting = true;
        try {
            client = new WebSocketClient(new URI(WS_URL)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.i(TAG, "Connected to local EchoService control channel");
                    connecting = false;
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "EchoService reply: " + message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.w(TAG, "EchoService WS closed (code=" + code + "): " + reason);
                    connecting = false;
                }

                @Override
                public void onError(Exception ex) {
                    Log.w(TAG, "EchoService WS error: " + ex.getMessage());
                    connecting = false;
                }
            };
            client.connect();
        } catch (Exception e) {
            Log.w(TAG, "Failed to connect to EchoService: " + e.getMessage());
            connecting = false;
        }
    }
}
