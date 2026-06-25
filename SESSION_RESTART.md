# Session Restart Guide — Child Helper App

## Quick Start (Every New Session)

### 1. Start Server
```powershell
cd C:\Work\child-app\project
Start-Process -NoNewWindow -FilePath ".\gradlew.bat" -ArgumentList ":server:run","--no-parallel" -RedirectStandardOutput "server.log" -RedirectStandardError "server_err.log"
Start-Sleep 8
curl.exe -s http://localhost:8080/api/v1/pairing/status/test
# Expected: {"error":"Session not found"}
```

### 2. Run Quick Start Script
```cmd
C:\Work\child-app\quick_start.bat
```
Or manually:
```powershell
$adb = "C:\Users\Nasko\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# Tunnels
& $adb -s 386c9711 reverse tcp:8080 tcp:8080
& $adb -s RZCX426L2BL reverse tcp:8080 tcp:8080

# Grant permissions
& $adb -s 386c9711 shell pm grant com.childhelper.app.child android.permission.CAMERA
& $adb -s 386c9711 shell pm grant com.childhelper.app.child android.permission.RECORD_AUDIO
& $adb -s 386c9711 shell pm grant com.childhelper.app.child android.permission.POST_NOTIFICATIONS
& $adb -s RZCX426L2BL shell pm grant com.childhelper.app.parent android.permission.CAMERA
& $adb -s RZCX426L2BL shell pm grant com.childhelper.app.parent android.permission.POST_NOTIFICATIONS

# Launch
& $adb -s 386c9711 shell am start -n com.childhelper.app.child/.ui.home.ChildHomeActivity
& $adb -s RZCX426L2BL shell am start -n com.childhelper.app.parent/.ui.dashboard.ParentDashboardActivity
```

### 3. Verify Connectivity
```powershell
# Check tunnels
& $adb -s 386c9711 reverse --list
& $adb -s RZCX426L2BL reverse --list
# Expected: UsbFfs tcp:8080 tcp:8080

# Check server
curl.exe -s http://localhost:8080/api/v1/pairing/status/test

# Check apps running
& $adb -s 386c9711 shell ps -A | Select-String childhelper
& $adb -s RZCX426L2BL shell ps -A | Select-String childhelper
```

### 4. Pair & Test
1. **Xiaomi**: Tap Pair → Generate Pairing Code (QR appears)
2. **Samsung**: Tap Pair New Device → Scan QR Code
3. **Samsung**: Tap Live View

**No need to re-pair if apps weren't cleared** — pairing persists in device storage.

---

## Device IDs

| Role | Device | ADB Serial | App Device ID |
|------|--------|------------|---------------|
| Child | Xiaomi 12 | `386c9711` | `d6e6d170-ae2f-497f-ae57-59264f2c43db` |
| Parent | Samsung S24 | `RZCX426L2BL` | `d0caf060-906d-4e73-8029-960ade757485` |

> ⚠️ Device IDs change if you `pm clear` the app data. Keep them consistent.

---

## Reading Traces

### Child App Traces
```powershell
adb -s 386c9711 shell "run-as com.childhelper.app.child cat files/call_trace.txt"
adb -s 386c9711 shell "run-as com.childhelper.app.child cat files/crash_log.txt"
```

### Parent App Traces
```powershell
adb -s RZCX426L2BL shell "run-as com.childhelper.app.parent cat files/lv_debug.txt"
```

### Server Logs
```powershell
Get-Content "C:\Work\child-app\server.log" -Tail 20
Get-Content "C:\Work\child-app\server_err.log" -Tail 10
```

### Clear Traces
```powershell
adb -s 386c9711 shell "run-as com.childhelper.app.child rm files/call_trace.txt"
adb -s RZCX426L2BL shell "run-as com.childhelper.app.parent rm files/lv_debug.txt"
```

---

## Build & Deploy

```powershell
cd C:\Work\child-app\project

# Build both
.\gradlew.bat :app:child:assembleDebug :app:parent:assembleDebug --no-parallel

# Install
adb -s 386c9711 install -r app\child\build\outputs\apk\debug\child-debug.apk
adb -s RZCX426L2BL install -r app\parent\build\outputs\apk\debug\parent-debug.apk

# Restart
adb -s 386c9711 shell am force-stop com.childhelper.app.child
adb -s RZCX426L2BL shell am force-stop com.childhelper.app.parent
adb -s 386c9711 shell am start -n com.childhelper.app.child/.ui.home.ChildHomeActivity
adb -s RZCX426L2BL shell am start -n com.childhelper.app.parent/.ui.dashboard.ParentDashboardActivity
```

---

## Server API Test Commands

```powershell
$cid = "d6e6d170-ae2f-497f-ae57-59264f2c43db"
$pid = "d0caf060-906d-4e73-8029-960ade757485"

# Initiate pairing
curl.exe -s -X POST http://localhost:8080/api/v1/pairing/initiate -H "Content-Type: application/json" -d "{\"childDeviceId\":\"$cid\",\"childPublicKey\":\"test\"}"

# Check session status
curl.exe -s http://localhost:8080/api/v1/pairing/status/SESSION_ID

# Send test offer to child
curl.exe -s -X POST http://localhost:8080/api/v1/signal/offer -H "Content-Type: application/json" -d "{\"type\":\"sdp\",\"messageId\":\"t\",\"fromDeviceId\":\"$pid\",\"toDeviceId\":\"$cid\",\"timestamp\":1,\"sessionId\":\"s\",\"sdpType\":\"OFFER\",\"sdp\":\"test\"}"

# Check pending queue
curl.exe -s http://localhost:8080/api/v1/signal/pending/$cid

# TURN credentials
curl.exe -s -X POST http://localhost:8080/api/v1/turn/credentials
```

---

## Troubleshooting

| Symptom | Check |
|---------|-------|
| "Connection failed" on parent | ADB tunnels lost → run quick_start.bat |
| Child app crash | Read `call_trace.txt` + `crash_log.txt` |
| Parent timeout | Read `lv_debug.txt` — check "pollAnswer" entries |
| Server not responding | `curl localhost:8080/api/v1/pairing/status/test` |
| Xiaomi blocks install | Accept "Install via USB" prompt on screen |
| Samsung shows 2 app icons | Use main icon (no Dual App badge) |
| Pairing session not found | Child uses P2P, not server — clear both apps, restart |
| No OkHttp logs on Samsung | Samsung suppresses app logs — use file traces instead |

---

## Key Findings from Previous Session

- **Live View IS working** — WebRTC connection completes after ~21 seconds
- **Video disabled** — CameraX (monitoring) conflicts with CameraVideoCapturer (WebRTC)
- **Audio not heard** — audio track is sent but parent doesn't play it (routing issue)
- **ADB tunnels drop silently** — always re-run quick_start.bat after USB reconnect
- **Samsung suppresses app logcat** — use `run-as cat files/*.txt` for debugging
- **Server must be restarted** after machine reboot
- **Pairing persists** across app restarts (no need to re-pair if data not cleared)
