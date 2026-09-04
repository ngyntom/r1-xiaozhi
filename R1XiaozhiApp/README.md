# Xiaozhi Voice Assistant for Phicomm R1

Tích hợp trợ lý giọng nói AI **Xiaozhi** vào loa **Phicomm R1**, biến nó thành smart speaker thông minh.

## ✨ Tính năng

- 🎙️ **Wake Word Detection** - Kích hoạt bằng "小智" (Xiao Zhi)
- ☁️ **Xiaozhi Cloud Integration** - Kết nối với Xiaozhi AI Cloud
- 🏠 **Self-hosted Support** - Hỗ trợ chạy server riêng
- 🔄 **Auto Fallback** - Tự động chuyển sang backup nếu mất kết nối
- 💡 **LED Status Indicator** - Hiển thị trạng thái qua LED
- 🔊 **Audio Playback** - Phát âm thanh TTS từ Xiaozhi
- 🚀 **Auto Start on Boot** - Tự động khởi động khi bật máy
- 🌐 **HTTP Control Panel** - Web interface để quản lý và xem pairing code

## 📱 Pairing với Xiaozhi Cloud

### Bước 1: Cài đặt APK lên R1
```bash
adb install -r R1Xiaozhi-v1.0.0-release.apk
```

### Bước 2: Lấy mã Pairing Code

**Cách 1: Xem qua ADB**
```bash
adb logcat | grep "Pairing Code"
# Output: XiaozhiConnection: XIAOZHI PAIRING CODE: 123456
```

**Cách 2: Truy cập Web UI**
```
http://[R1_IP_ADDRESS]:8088
```

Bạn sẽ thấy **mã 6 số** hiển thị to và rõ ràng.

### Bước 3: Thêm thiết bị vào Xiaozhi Console

1. Truy cập https://xiaozhi.me/console/agents
2. Tạo Agent mới (hoặc chọn Agent hiện có)
3. Click **"Add Device"**
4. Nhập **mã 6 số** từ R1
5. ✅ Hoàn tất!

### Bước 4: Cấu hình Agent

```
Dialogue Language: Vietnamese
Voice Role: Giọng nữ (hoặc nam)
Role Introduction:
  Tôi là {{assistant_name}}, trợ lý ảo thông minh trên loa Phicomm R1.
  Tôi có giọng nói dễ nghe, thích dùng câu ngắn gọn và luôn sẵn sàng giúp đỡ bạn.
```

## 🚀 Quick Start

### Download APK
```bash
# Download từ GitHub Releases
wget https://github.com/xuan2261/r1-xiaozhi/releases/latest/download/R1Xiaozhi-v1.0.0-release.apk
```

### Cài đặt
```bash
# Kết nối R1 qua ADB
adb connect 192.168.1.XXX:5555

# Cài APK
adb install -r R1Xiaozhi-v1.0.0-release.apk

# Grant permissions
adb shell pm grant com.phicomm.r1.xiaozhi android.permission.RECORD_AUDIO
adb shell pm grant com.phicomm.r1.xiaozhi android.permission.WRITE_EXTERNAL_STORAGE
```

### Khởi động
```bash
# Start services
adb shell am start -n com.phicomm.r1.xiaozhi/.ui.MainActivity
```

### Xem Pairing Code
```bash
# Qua ADB
adb logcat | grep "PAIRING CODE"

# Hoặc mở browser
# http://192.168.1.XXX:8088
```

## 📖 Hướng dẫn chi tiết

Xem file [`HUONG_DAN_CAI_DAT.md`](../HUONG_DAN_CAI_DAT.md) để có hướng dẫn đầy đủ về:
- Chuẩn bị môi trường phát triển
- Build từ source code
- Cấu hình nâng cao
- Troubleshooting
- Root và LED control

## 🏗️ Build từ Source

```bash
# Clone repository
git clone https://github.com/xuan2261/r1-xiaozhi.git
cd r1-xiaozhi/R1XiaozhiApp

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease

# APK output
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

## 🌐 HTTP API Endpoints

Sau khi cài app, bạn có thể access các endpoints này:

- `http://[R1_IP]:8088/` - Home page với pairing code
- `http://[R1_IP]:8088/pairing` - JSON pairing info
- `http://[R1_IP]:8088/status` - Device status
- `http://[R1_IP]:8088/config` - Configuration
- `http://[R1_IP]:8088/start` - Start services
- `http://[R1_IP]:8088/stop` - Stop services

## 🔧 Tech Stack

- **Android API 22** (Lollipop 5.1) - Target cho Phicomm R1
- **Rockchip RK3229** - R1's chipset (ARMv7)
- **Java 7** - Backward compatibility
- **OkHttp 3.12.13** - WebSocket communication
- **NanoHTTPD** - Embedded HTTP server
- **Gson** - JSON parsing

## 📦 Dependencies

```gradle
// Network
compile 'com.squareup.okhttp3:okhttp:3.12.13'
compile 'com.google.code.gson:gson:2.8.5'

// HTTP Server
compile 'org.nanohttpd:nanohttpd:2.3.1'
compile 'org.nanohttpd:nanohttpd-websocket:2.3.1'

// Logging
compile 'com.jakewharton.timber:timber:4.5.1'
```

## 📂 Project Structure

```
R1XiaozhiApp/
├── app/
│   └── src/main/
│       ├── java/com/phicomm/r1/xiaozhi/
│       │   ├── config/          # Configuration
│       │   ├── service/         # Background services
│       │   ├── ui/              # Activities
│       │   ├── receiver/        # Broadcast receivers
│       │   └── util/            # Utilities
│       ├── res/                 # Resources
│       └── AndroidManifest.xml
├── build.gradle
└── README.md
```

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License

MIT License - Free to use and modify

## 🙏 Credits

- **Xiaozhi AI** - https://xiaozhi.me
- **Phicomm R1** - Hardware platform
- **Community** - Testing and feedback

## 📞 Support

- **Documentation**: [HUONG_DAN_CAI_DAT.md](../HUONG_DAN_CAI_DAT.md)
- **Issues**: https://github.com/xuan2261/r1-xiaozhi/issues
- **Xiaozhi Docs**: https://stable-learn.com/en/py-xiaozhi-guide/

---

**Made with ❤️ for Phicomm R1 Community**