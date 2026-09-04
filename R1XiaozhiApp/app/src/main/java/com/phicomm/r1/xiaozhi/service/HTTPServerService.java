package com.phicomm.r1.xiaozhi.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.phicomm.r1.xiaozhi.config.XiaozhiConfig;
import com.phicomm.r1.xiaozhi.core.XiaozhiCore;
import com.phicomm.r1.xiaozhi.util.PairingCodeGenerator;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

/**
 * HTTP Server / Web UI điều khiển Xiaozhi Voice Assistant
 *
 * Cung cấp:
 * - Trang dashboard HTML (/)
 * - REST API để điều khiển từ trình duyệt web
 */
public class HTTPServerService extends Service {

    private static final String TAG = "HTTPServer";
    private static final int PORT = 8081;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private boolean isRunning = false;

    private XiaozhiConfig config;
    private XiaozhiCore core;

    @Override
    public void onCreate() {
        super.onCreate();
        config = new XiaozhiConfig(this);
        core = XiaozhiCore.getInstance();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            config = new XiaozhiConfig(this);
            startServer();
        }
        return START_STICKY;
    }

    private void startServer() {
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    isRunning = true;
                    Log.i(TAG, "Web UI / HTTP Server started on port " + PORT);

                    while (isRunning && !serverSocket.isClosed()) {
                        try {
                            Socket clientSocket = serverSocket.accept();
                            handleClient(clientSocket);
                        } catch (IOException e) {
                            if (isRunning) {
                                Log.e(TAG, "Error accepting client: " + e.getMessage());
                            }
                        }
                    }

                } catch (IOException e) {
                    Log.e(TAG, "Failed to start server: " + e.getMessage(), e);
                } finally {
                    isRunning = false;
                }
            }
        });
        serverThread.start();
    }

    private void handleClient(Socket clientSocket) {
        try {
            clientSocket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), "UTF-8"));
            PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true);

            String requestLine = reader.readLine();
            if (requestLine == null) {
                clientSocket.close();
                return;
            }

            Log.d(TAG, "Request: " + requestLine);

            // Đọc headers và tìm Content-Length
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    } catch (NumberFormatException e) {
                        contentLength = 0;
                    }
                }
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(writer, 400, "Bad Request");
                clientSocket.close();
                return;
            }

            String method = parts[0];
            String pathAndQuery = parts[1];

            // Tách path khỏi query string
            String path = pathAndQuery;
            String query = "";
            int qIndex = pathAndQuery.indexOf('?');
            if (qIndex >= 0) {
                path = pathAndQuery.substring(0, qIndex);
                query = pathAndQuery.substring(qIndex + 1);
            }

            // Đọc body nếu có (cho POST)
            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = reader.read(buf, 0, contentLength);
                if (read > 0) {
                    body = new String(buf, 0, read);
                }
            }

            routeRequest(writer, method, path, query, body);

            clientSocket.close();

        } catch (IOException e) {
            Log.e(TAG, "Error handling client: " + e.getMessage());
        }
    }

    private void routeRequest(PrintWriter writer, String method, String path, String query, String body) {
        try {
            // Dashboard
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                serveDashboard(writer);
                return;
            }

            // Trạng thái đầy đủ
            if ("GET".equals(method) && "/status".equals(path)) {
                serveStatus(writer);
                return;
            }

            // Cấu hình
            if ("GET".equals(method) && "/config".equals(path)) {
                serveConfig(writer);
                return;
            }

            // Cập nhật cấu hình (POST) - wake word, URL, mode, LED, auto-start
            if ("POST".equals(method) && "/update-config".equals(path)) {
                serveUpdateConfig(writer, body);
                return;
            }

            // Bật/tắt listening (wake word detection)
            if ("POST".equals(method) && "/voice".equals(path)) {
                serveSetListening(writer, body);
                return;
            }

            // Gửi câu lệnh text tới Xiaozhi
            if ("POST".equals(method) && "/send-text".equals(path)) {
                serveSendText(writer, body);
                return;
            }

            // Điều chỉnh LED thủ công
            if ("POST".equals(method) && "/led".equals(path)) {
                serveSetLed(writer, body);
                return;
            }

            // Kết nối/ngắt kết nối WebSocket
            if ("POST".equals(method) && "/connect".equals(path)) {
                serveConnect(writer, body);
                return;
            }

            // Giữ lại endpoints pairing gốc
            if ("GET".equals(method) && "/pairing-code".equals(path)) {
                servePairingCode(writer);
                return;
            }

            if ("POST".equals(method) && "/reset".equals(path)) {
                serveResetPairing(writer);
                return;
            }

            sendResponse(writer, 404, "Not Found");

        } catch (Exception e) {
            Log.e(TAG, "Route error: " + e.getMessage(), e);
            try {
                sendResponse(writer, 500, "Internal Server Error: " + e.getMessage());
            } catch (Exception ex) {
                // ignore
            }
        }
    }

    // ==================== Dashboard HTML ====================

    private void serveDashboard(PrintWriter writer) {
        String html = buildDashboardHtml();
        int byteLen;
        try {
            byteLen = html.getBytes("UTF-8").length;
        } catch (java.io.UnsupportedEncodingException e) {
            byteLen = html.length();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: text/html; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(byteLen).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        sb.append(html);
        writer.print(sb.toString());
    }

    private String buildDashboardHtml() {
        return "" +
        "<!DOCTYPE html>" +
        "<html lang=\"vi\">" +
        "<head>" +
        "<meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<title>R1 Xiaozhi - Điều Khiển</title>" +
        "<style>" +
        "*{box-sizing:border-box;margin:0;padding:0}" +
        "body{font-family:'Segoe UI',Arial,sans-serif;background:linear-gradient(135deg,#0f2027,#203a43,#2c5364);min-height:100vh;color:#e8e8e8;padding:20px}" +
        ".container{max-width:720px;margin:0 auto}" +
        "h1{text-align:center;margin:10px 0 4px;font-size:24px;color:#fff}" +
        ".sub{text-align:center;color:#9ab;font-size:13px;margin-bottom:20px}" +
        ".card{background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.12);" +
        "border-radius:12px;padding:18px;margin-bottom:16px;backdrop-filter:blur(4px)}" +
        ".card h2{font-size:16px;margin-bottom:12px;color:#7fd0ff;border-bottom:1px solid rgba(255,255,255,.1);padding-bottom:8px}" +
        ".row{display:flex;flex-wrap:wrap;gap:10px;align-items:center}" +
        "label{font-size:13px;color:#bcd;display:block;margin:8px 0 4px}" +
        "input[type=text],input[type=url],select{width:100%;padding:9px 12px;border:none;border-radius:8px;" +
        "background:#1a2632;color:#fff;font-size:14px;border:1px solid #2b3b4a}" +
        "input:focus,select:focus{outline:none;border-color:#4aa3df}" +
        "button{padding:10px 16px;border:none;border-radius:8px;font-size:14px;cursor:pointer;color:#fff;transition:opacity .2s}" +
        "button:hover{opacity:.85}" +
        ".btn-b{background:#2e86c1}.btn-g{background:#28b463}.btn-r{background:#c0392b}" +
        ".btn-o{background:#d68910}.btn-d{background:#6c7a89}.btn-s{background:#8e44ad}" +
        ".status-box{background:#10181f;border-radius:8px;padding:12px 14px;font-family:monospace;font-size:13px;white-space:pre-wrap;line-height:1.5}" +
        ".badge{display:inline-block;padding:3px 10px;border-radius:20px;font-size:12px;font-weight:600}" +
        ".ok{background:#145a32;color:#7ef0a0}.err{background:#641e16;color:#ff9a94}.idle{background:#4a3700;color:#ffd08a}" +
        ".warn{background:#3d3d1a;color:#f2e88b}" +
        ".log{background:#0b1217;border-radius:8px;padding:10px;font-family:monospace;font-size:12px;height:120px;overflow-y:auto;margin-top:10px}" +
        ".footer{text-align:center;color:#667;font-size:12px;margin-top:10px}" +
        "</style>" +
        "</head>" +
        "<body><div class=\"container\">" +
        "<h1>R1 Xiaozhi Assistant</h1>" +
        "<div class=\"sub\">Trang điều khiển qua Web - truy cập từ thiết bị cùng mạng WiFi</div>" +

        "<div class=\"card\">" +
        "<h2>Trạng Thái</h2>" +
        "<div class=\"status-box\" id=\"statusBox\">Đang tải...</div>" +
        "</div>" +

        "<div class=\"card\">" +
        "<h2>Điều Khiển Nhanh</h2>" +
        "<div class=\"row\">" +
        "<button class=\"btn-g\" onclick=\"setVoice(true)\">Bật Đánh Thức</button>" +
        "<button class=\"btn-r\" onclick=\"setVoice(false)\">Tắt Đánh Thức</button>" +
        "<button class=\"btn-o\" onclick=\"sendText('Bây giờ là mấy giờ')\">Hỏi giờ</button>" +
        "<button class=\"btn-s\" onclick=\"setLed()\">Đổi LED</button>" +
        "</div>" +
        "<div class=\"row\" style=\"margin-top:10px\">" +
        "<button class=\"btn-b\" onclick=\"refresh()\">Làm Mới</button>" +
        "</div>" +
        "</div>" +

        "<div class=\"card\">" +
        "<h2>Gửi Câu Lệnh</h2>" +
        "<input type=\"text\" id=\"cmdText\" placeholder=\"Nhập câu lệnh...\">" +
        "<div class=\"row\" style=\"margin-top:10px\">" +
        "<button class=\"btn-b\" onclick=\"sendCmd()\">Gửi</button>" +
        "</div>" +
        "</div>" +

        "<div class=\"card\">" +
        "<h2>Cấu Hình</h2>" +
        "<form id=\"configForm\">" +
        "<label>Từ Đánh Thức (Wake Word) - chỉ hiển thị, detection là phát hiện tiếng động</label>" +
        "<input type=\"text\" id=\"wakeWord\" name=\"wake_word\" placeholder=\"小智\">" +

        "<label>Chế Độ Kết Nối</label>" +
        "<select id=\"useCloud\" name=\"use_cloud\">" +
        "<option value=\"true\">Cloud (xiaozhi.me)</option>" +
        "<option value=\"false\">Self-hosted</option>" +
        "</select>" +

        "<label>URL Cloud</label>" +
        "<input type=\"url\" id=\"cloudUrl\" name=\"cloud_url\" placeholder=\"wss://xiaozhi.me/v1/ws\">" +

        "<label>URL Self-hosted</label>" +
        "<input type=\"url\" id=\"selfUrl\" name=\"self_hosted_url\" placeholder=\"ws://IP:8081/websocket\">" +

        "<div class=\"row\" style=\"margin-top:12px\">" +
        "<label style=\"display:flex;align-items:center;margin:0;width:auto\">" +
        "<input type=\"checkbox\" id=\"ledEnabled\" name=\"led_enabled\" style=\"width:auto;margin-right:8px\"> LED" +
        "</label>" +
        "<label style=\"display:flex;align-items:center;margin:0;width:auto\">" +
        "<input type=\"checkbox\" id=\"autoStart\" name=\"auto_start\" style=\"width:auto;margin-right:8px\"> Tự động khởi động" +
        "</label>" +
        "</div>" +

        "<div class=\"row\" style=\"margin-top:12px\">" +
        "<button class=\"btn-g\" type=\"submit\">Lưu Cấu Hình</button>" +
        "</div>" +
        "</form>" +
        "</div>" +

        "<div class=\"card\">" +
        "<h2>Nhật Ký Server</h2>" +
        "<div class=\"log\" id=\"logBox\"></div>" +
        "</div>" +

        "<div class=\"footer\">R1 Xiaozhi Assistant - Web UI v1.0</div>" +
        "</div>" +

        "<script>" +
        "function req(method,url,data,cb){" +
        "var x=new XMLHttpRequest();x.onreadystatechange=function(){" +
        "if(x.readyState===4){var r=null;try{r=JSON.parse(x.responseText)}catch(e){}cb(r,x.status)}" +
        "};x.open(method,url,true);" +
        "if(method==='POST'){x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');" +
        "x.send(typeof data==='string'?data:Object.keys(data).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(data[k])}).join('&'));" +
        "}else{x.send();}" +
        "}" +
        "function log(msg){var l=document.getElementById('logBox');l.innerHTML+='['+new Date().toLocaleTimeString()+'] '+msg+'\\n';l.scrollTop=l.scrollHeight;}" +
        "function refresh(){req('GET','/status',null,function(r,s){" +
        "if(r){" +
        "var st=r.status||{};var badge=r.connected?'<span class=\"badge ok\">Connected</span>':'<span class=\"badge err\">Disconnected</span>';" +
        "document.getElementById('statusBox').innerHTML=" +
        "'Device: '+(r.device_id||'-')+' | '+badge+'\\n'+" +
        "'State: '+(r.state||'-')+' | Listening: '+(r.listening?'ON':'OFF')+'\\n'+" +
        "'Wake Word: '+(r.wake_word||'-')+'\\n'+" +
        "'Mode: '+(r.use_cloud?'Cloud':'Self-hosted')+'\\n'+" +
        "'URL: '+(r.active_url||'-')+'\\n'+" +
        "'LED: '+(r.led_enabled?'Enabled':'Disabled')+' | AutoStart: '+(r.auto_start?'ON':'OFF')" +
        "}" +
        "});" +
        "req('GET','/config',null,function(r,s){" +
        "if(r){document.getElementById('wakeWord').value=r.wake_word||'';" +
        "document.getElementById('cloudUrl').value=r.cloud_url||'';" +
        "document.getElementById('selfUrl').value=r.self_hosted_url||'';" +
        "document.getElementById('useCloud').value=String(r.use_cloud);" +
        "document.getElementById('ledEnabled').checked=!!r.led_enabled;" +
        "document.getElementById('autoStart').checked=!!r.auto_start;}" +
        "});" +
        "}" +
        "function setVoice(on){req('POST','/voice',{listening:on},function(r,s){log((r&&r.message)||'voice');refresh();});}" +
        "function sendText(t){req('POST','/send-text',{text:t},function(r,s){log((r&&r.message)||'sent');});}" +
        "function sendCmd(){var t=document.getElementById('cmdText').value;if(t){sendText(t);document.getElementById('cmdText').value='';}}" +
        "function setLed(){req('POST','/led',{action:'cycle'},function(r,s){log((r&&r.message)||'led');});}" +
        "document.getElementById('configForm').addEventListener('submit',function(e){" +
        "e.preventDefault();var f=this;" +
        "var data={wake_word:document.getElementById('wakeWord').value," +
        "use_cloud:document.getElementById('useCloud').value," +
        "cloud_url:document.getElementById('cloudUrl').value," +
        "self_hosted_url:document.getElementById('selfUrl').value," +
        "led_enabled:document.getElementById('ledEnabled').checked?'true':'false'," +
        "auto_start:document.getElementById('autoStart').checked?'true':'false'};" +
        "req('POST','/update-config',data,function(r,s){log((r&&r.message)||'saved');});" +
        "},false);" +
        "refresh();setInterval(refresh,5000);" +
        "</script>" +
        "</body></html>";
    }

    // ==================== API Handlers ====================

    /**
     * GET /status - trả về trạng thái đầy đủ
     */
    private void serveStatus(PrintWriter writer) throws JSONException {
        boolean isPaired = PairingCodeGenerator.isPaired(this);
        String deviceId = PairingCodeGenerator.getDeviceId(this);
        boolean listening = isVoiceListening();
        boolean connected = isWebSocketConnected();

        JSONObject r = new JSONObject();
        r.put("paired", isPaired);
        r.put("device_id", deviceId);
        r.put("connected", connected);
        r.put("state", core != null && core.getDeviceState() != null ? core.getDeviceState().name() : "UNKNOWN");
        r.put("listening", listening);
        r.put("wake_word", config.getWakeWord());
        r.put("use_cloud", config.isUseCloud());
        r.put("active_url", config.getActiveUrl());
        r.put("led_enabled", config.isLedEnabled());
        r.put("auto_start", config.isAutoStart());
        sendJsonResponse(writer, 200, r.toString());
    }

    /**
     * GET /config - trả về cấu hình hiện tại
     */
    private void serveConfig(PrintWriter writer) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("use_cloud", config.isUseCloud());
        r.put("cloud_url", config.getCloudUrl());
        r.put("self_hosted_url", config.getSelfHostedUrl());
        r.put("wake_word", config.getWakeWord());
        r.put("auto_start", config.isAutoStart());
        r.put("led_enabled", config.isLedEnabled());
        r.put("http_port", config.getHttpServerPort());
        sendJsonResponse(writer, 200, r.toString());
    }

    /**
     * POST /update-config - cập nhật cấu hình
     */
    private void serveUpdateConfig(PrintWriter writer, String body) throws JSONException {
        JSONObject params = parseBody(body);

        if (params.has("wake_word")) {
            String ww = params.optString("wake_word");
            if (ww != null && !ww.isEmpty()) {
                config.setWakeWord(ww);
                Log.i(TAG, "Wake word updated to: " + ww);
                // Thông báo cho VoiceRecognitionService refresh notification
                Intent i = new Intent(this, VoiceRecognitionService.class);
                i.setAction(VoiceRecognitionService.ACTION_UPDATE_WAKE_WORD);
                startService(i);
            }
        }

        if (params.has("use_cloud")) {
            config.setUseCloud(Boolean.parseBoolean(params.optString("use_cloud")));
        }
        if (params.has("cloud_url") && !params.optString("cloud_url").isEmpty()) {
            config.setCloudUrl(params.optString("cloud_url"));
        }
        if (params.has("self_hosted_url") && !params.optString("self_hosted_url").isEmpty()) {
            config.setSelfHostedUrl(params.optString("self_hosted_url"));
        }
        if (params.has("led_enabled")) {
            config.setLedEnabled(Boolean.parseBoolean(params.optString("led_enabled")));
        }
        if (params.has("auto_start")) {
            config.setAutoStart(Boolean.parseBoolean(params.optString("auto_start")));
        }

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("message", "Cấu hình đã được lưu");
        r.put("config", config.exportConfig());
        sendJsonResponse(writer, 200, r.toString());
    }

    /**
     * POST /voice - bật/tắt listening (wake word detection)
     */
    private void serveSetListening(PrintWriter writer, String body) throws JSONException {
        JSONObject params = parseBody(body);
        boolean listening = !params.has("listening") || Boolean.parseBoolean(params.optString("listening"));

        Intent i = new Intent(this, VoiceRecognitionService.class);
        i.setAction(VoiceRecognitionService.ACTION_SET_LISTENING);
        i.putExtra(VoiceRecognitionService.EXTRA_LISTENING, listening);
        startService(i);

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("listening", listening);
        r.put("message", listening ? "Đã bật nghe từ đánh thức" : "Đã tắt nghe từ đánh thức");
        sendJsonResponse(writer, 200, r.toString());
    }

    /**
     * POST /send-text - gửi câu lệnh text tới Xiaozhi
     */
    private void serveSendText(PrintWriter writer, String body) throws JSONException {
        JSONObject params = parseBody(body);
        String text = params.optString("text");

        JSONObject r = new JSONObject();
        if (text == null || text.isEmpty()) {
            r.put("success", false);
            r.put("message", "Thiếu tham số text");
            sendJsonResponse(writer, 400, r.toString());
            return;
        }

        XiaozhiConnectionService cs = core != null ? core.getConnectionService() : null;
        if (cs != null && cs.isConnected()) {
            cs.sendTextMessage(text);
            r.put("success", true);
            r.put("message", "Đã gửi: " + text);
        } else {
            r.put("success", false);
            r.put("message", "Chưa kết nối Xiaozhi");
        }
        sendJsonResponse(writer, 200, r.toString());
    }

    /**
     * POST /led - điều chỉnh LED thủ công
     * body: action=cycle | color=RRGGBB | state=idle/listening/thinking/speaking/error
     */
    private void serveSetLed(PrintWriter writer, String body) throws JSONException {
        JSONObject params = parseBody(body);
        String action = params.optString("action");

        Intent ledIntent = new Intent(this, LEDControlService.class);

        if ("cycle".equals(action) || params.has("color")) {
            // Chuyển màu qua từng trạng thái
            String state = params.optString("state");
            if ("idle".equals(state)) {
                ledIntent.setAction(LEDControlService.ACTION_SET_IDLE);
            } else if ("listening".equals(state)) {
                ledIntent.setAction(LEDControlService.ACTION_SET_LISTENING);
            } else if ("thinking".equals(state)) {
                ledIntent.setAction(LEDControlService.ACTION_SET_THINKING);
            } else if ("speaking".equals(state)) {
                ledIntent.setAction(LEDControlService.ACTION_SET_SPEAKING);
            } else if ("error".equals(state)) {
                ledIntent.setAction(LEDControlService.ACTION_SET_ERROR);
            } else {
                // Default: cycle các màu theo trình tự
                ledIntent.setAction(LEDControlService.ACTION_SET_COLOR);
                ledIntent.putExtra("color", cycleColor());
            }
        } else {
            ledIntent.setAction(LEDControlService.ACTION_SET_IDLE);
        }

        startService(ledIntent);

        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("message", "Đã chuyển LED");
        sendJsonResponse(writer, 200, r.toString());
    }

    private int ledCycleIndex = 0;
    private synchronized int cycleColor() {
        int[] colors = {0x0066CC, 0x00FF00, 0xFFFFFF, 0x00FFFF, 0xFF0000, 0xFF00FF};
        int c = colors[ledCycleIndex % colors.length];
        ledCycleIndex++;
        return c;
    }

    /**
     * POST /connect - kết nối/ngắt WebSocket
     * body: connected=true|false
     */
    private void serveConnect(PrintWriter writer, String body) throws JSONException {
        JSONObject params = parseBody(body);
        boolean connected = params.has("connected") && Boolean.parseBoolean(params.optString("connected"));
        JSONObject r = new JSONObject();

        XiaozhiConnectionService cs = core != null ? core.getConnectionService() : null;
        if (cs != null) {
            if (connected) {
                cs.connect();
                r.put("success", true);
                r.put("message", "Đang kết nối...");
            } else {
                cs.disconnect();
                r.put("success", true);
                r.put("message", "Đã ngắt kết nối");
            }
        } else {
            r.put("success", false);
            r.put("message", "Service chưa sẵn sàng");
        }
        sendJsonResponse(writer, 200, r.toString());
    }

    // ==================== Các helper ====================

    private boolean isVoiceListening() {
        XiaozhiCore c = XiaozhiCore.getInstance();
        if (c != null && c.getVoiceService() != null) {
            return c.getVoiceService().isListening();
        }
        return false;
    }

    private boolean isWebSocketConnected() {
        XiaozhiCore c = XiaozhiCore.getInstance();
        if (c != null && c.getConnectionService() != null) {
            return c.getConnectionService().isConnected();
        }
        return false;
    }

    /**
     * Parse body dạng application/x-www-form-urlencoded hoặc JSON
     */
    private JSONObject parseBody(String body) {
        JSONObject obj = new JSONObject();
        if (body == null || body.isEmpty()) {
            return obj;
        }

        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            try {
                obj = new JSONObject(trimmed);
            } catch (JSONException e) {
                Log.e(TAG, "Invalid JSON body: " + e.getMessage());
            }
            return obj;
        }

        // Parse form-urlencoded
        try {
            String[] pairs = trimmed.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    obj.put(key, value);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse body: " + e.getMessage());
        }
        return obj;
    }

    /**
     * GET /pairing-code
     */
    private void servePairingCode(PrintWriter writer) throws JSONException {
        String deviceId = PairingCodeGenerator.getDeviceId(this);
        String pairingCode = PairingCodeGenerator.getPairingCode(this);
        boolean isPaired = PairingCodeGenerator.isPaired(this);

        JSONObject response = new JSONObject();
        response.put("device_id", deviceId);
        response.put("pairing_code", pairingCode);
        response.put("paired", isPaired);

        sendJsonResponse(writer, 200, response.toString());
        Log.d(TAG, "Served pairing code: " + pairingCode);
    }

    /**
     * POST /reset
     */
    private void serveResetPairing(PrintWriter writer) throws JSONException {
        PairingCodeGenerator.resetPairing(this);

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", "Pairing reset successfully");
        sendJsonResponse(writer, 200, response.toString());
        Log.i(TAG, "Pairing reset via HTTP");
    }

    private void sendResponse(PrintWriter writer, int statusCode, String statusMessage) {
        writer.println("HTTP/1.1 " + statusCode + " " + statusMessage);
        writer.println("Content-Type: text/plain");
        writer.println("Connection: close");
        writer.println();
        writer.println(statusMessage);
    }

    private void sendJsonResponse(PrintWriter writer, int statusCode, String json) {
        String statusMessage = statusCode == 200 ? "OK" : "Error";
        int byteLen;
        try {
            byteLen = json.getBytes("UTF-8").length;
        } catch (java.io.UnsupportedEncodingException e) {
            byteLen = json.length();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMessage).append("\r\n");
        sb.append("Content-Type: application/json; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(byteLen).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        sb.append(json);
        writer.print(sb.toString());
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
        Log.i(TAG, "HTTP Server stopped");
    }

    private void stopServer() {
        isRunning = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket: " + e.getMessage());
            }
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }
}
