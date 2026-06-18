# Setup Guide — Privacy-First Child Helper

> **Estimated time:** 30–45 minutes for a clean setup  
> **Last verified:** Android Studio Ladybug Feature Drop | 2024.2.2

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Development Environment Setup](#2-development-environment-setup)
3. [Project Setup](#3-project-setup)
4. [Firebase Configuration](#4-firebase-configuration)
5. [Backend API Configuration](#5-backend-api-configuration)
6. [`local.properties` Template](#6-localproperties-template)
7. [Build Variants](#7-build-variants)
8. [Running the App](#8-running-the-app)
9. [Recommended Test Devices](#9-recommended-test-devices)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Prerequisites

| Requirement | Details |
|-------------|---------|
| **Operating System** | macOS 12+ (Monterey) / Ubuntu 22.04+ / Windows 11 with WSL2 |
| **Android Studio** | Ladybug (2024.2.1) or newer — [Download](https://developer.android.com/studio) |
| **JDK** | OpenJDK 17 (Eclipse Temurin / Adoptium recommended) |
| **Android SDK** | API Level 36 (Android 16) + Build-Tools 36.x |
| **Git** | 2.30 or newer |
| **Firebase account** | Free Spark plan — [Sign up](https://console.firebase.google.com) |
| **Backend server** *(Optional)* | Fly.io / Railway / self-hosted VPS (Ubuntu 22.04+) |
| **TURN server** *(Optional)* | coturn (self-hosted) or Twilio TURN (managed) |

> **Tip:** The app can run in **fully offline mode** using local WebRTC signaling. Firebase and a backend server are only required for push notifications and cloud relay.

---

## 2. Development Environment Setup

### 2.1 Install Android Studio

1. Download Android Studio Ladybug from the [official site](https://developer.android.com/studio).
2. Run the installer and follow the setup wizard.
3. On first launch, choose **Standard** installation when prompted.
4. In **SDK Manager** (`Tools > SDK Manager`), install:
   - **SDK Platforms:** Android 16.0 (API 36)
   - **SDK Tools:**
     - Android SDK Build-Tools 36
     - Android SDK Platform-Tools
     - Android Emulator
     - Android SDK Command-line Tools
     - CMake (latest)
     - NDK (latest) — required for WebRTC native libraries

### 2.2 Install JDK 17

**macOS (Homebrew):**

```bash
brew install --cask temurin@17
```

**Linux (Ubuntu/Debian):**

```bash
sudo apt update
sudo apt install -y wget apt-transport-https
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo tee /etc/apt/keyrings/adoptium.asc
echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install temurin-17-jdk
```

**Windows (WSL2):**

```bash
# Within WSL2 Ubuntu — same commands as Linux above
sudo apt update && sudo apt install temurin-17-jdk
```

### 2.3 Set Environment Variables

**macOS / Linux** — add to `~/.zshrc` or `~/.bashrc`:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "/usr/lib/jvm/temurin-17-jdk-amd64")
export ANDROID_HOME="$HOME/Library/Android/sdk"   # macOS
# export ANDROID_HOME="$HOME/Android/Sdk"          # Linux
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

**Windows** — add to System Environment Variables:

| Variable | Value |
|----------|-------|
| `JAVA_HOME` | `C:\Program Files\Eclipse Adoptium\jdk-17` |
| `ANDROID_HOME` | `%LOCALAPPDATA%\Android\Sdk` |
| `Path` | Append `%ANDROID_HOME%\platform-tools` |

Reload your shell or run `source ~/.zshrc` to apply changes.

### 2.4 Verify Installation

```bash
# Verify JDK
java -version
# Expected: openjdk version "17.0.x" ...

# Verify Android SDK tools
adb version
# Expected: Android Debug Bridge version 1.0.x

# Verify Gradle availability
./gradlew --version
# Expected: Gradle 8.x + Kotlin 2.x
```

> All three commands should report clean output with no errors before proceeding.

---

## 3. Project Setup

### 3.1 Clone the Repository

```bash
git clone https://github.com/your-org/privacy-first-child-helper.git
cd privacy-first-child-helper
```

### 3.2 Open in Android Studio

1. Launch Android Studio.
2. Select **Open** and choose the `privacy-first-child-helper` directory.
3. Wait for the IDE to index files and detect the project structure.

### 3.3 Sync Gradle

Android Studio should auto-prompt to sync. If not:

```bash
# Command-line sync (recommended first time)
./gradlew sync
```

> **First sync may take 5–10 minutes** as Gradle downloads dependencies, resolves the Compose compiler, and builds native WebRTC modules. A stable internet connection is required.

### 3.4 Build the Project

```bash
# Build all modules
./gradlew assembleDebug

# Or via Android Studio: Build > Make Project (Ctrl+F9)
```

A successful build produces two APKs:
- `app-child/build/outputs/apk/debug/app-child-debug.apk`
- `app-parent/build/outputs/apk/debug/app-parent-debug.apk`

---

## 4. Firebase Configuration

Firebase provides **Cloud Messaging (FCM)** for push notifications and optional **Realtime Database / Firestore** for signaling fallbacks.

### 4.1 Create a Firebase Project

1. Go to the [Firebase Console](https://console.firebase.google.com).
2. Click **Add project** and name it (e.g., `privacy-child-helper`).
3. Disable **Google Analytics** (privacy-first design — analytics are opt-in only).
4. Click **Create project**.

### 4.2 Register Android Apps

You must register **two separate apps** — one for each module:

| App | Package Name | Module Path |
|-----|-------------|-------------|
| Child App | `com.childhelper.child` | `app-child/` |
| Parent App | `com.childhelper.parent` | `app-parent/` |

**For each app:**

1. In Firebase Console, click the **Android icon** to add an app.
2. Enter the package name from the table above.
3. Enter an app nickname (e.g., "Child Helper — Child").
4. Skip **Debug signing certificate** for now (see §4.4).
5. Click **Register app**.
6. Download the `google-services.json` file.

### 4.3 Place Configuration Files

```bash
# Child app
cp ~/Downloads/google-services.json app-child/

# Parent app (rename second downloaded file)
cp ~/Downloads/google-services-parent.json app-parent/google-services.json
```

Verify the structure:

```
privacy-first-child-helper/
├── app-child/
│   ├── google-services.json          ← child config
│   └── src/...
├── app-parent/
│   ├── google-services.json          ← parent config
│   └── src/...
```

### 4.4 Add Debug Signing Certificate (Optional — for Auth)

If using Firebase Authentication or App Check:

```bash
# Get your debug SHA-1 certificate
keytool -alias androiddebugkey -keystore ~/.android/debug.keystore -list -v
# Default password: android
```

Copy the **SHA-1** and **SHA-256** fingerprints into each Firebase app:
**Project Settings > Your App > SHA certificate fingerprints > Add fingerprint**

### 4.5 Enable Firebase Cloud Messaging

1. In Firebase Console, go to **Project Settings > Cloud Messaging**.
2. Note down the **Server Key** — you will need this for your backend.
3. Ensure **Cloud Messaging API (V1)** is enabled.

### 4.6 Verify Firebase Setup

Rebuild the project:

```bash
./gradlew clean assembleDebug
```

If the build succeeds and `FirebaseApp` initializes without crashes, the configuration is correct.

---

## 5. Backend API Configuration

The backend provides **TURN relay**, **signaling coordination**, and **FCM push routing**. If you are using the **offline local-signaling mode**, skip this section.

### 5.1 Set `API_BASE_URL`

Add to `local.properties` in the project root:

```properties
API_BASE_URL=https://api.childhelper.com
```

For local development with a backend running on your machine:

```properties
API_BASE_URL=http://10.0.2.2:8080        # Android Emulator → localhost
```

### 5.2 SSL Certificate Pinning

Certificate pinning protects against MITM attacks on the API connection.

**Generate the SHA-256 hash of your server's certificate:**

```bash
# Method 1: From live server
openssl s_client -connect api.childhelper.com:443 -servername api.childhelper.com \
  </dev/null 2>/dev/null | openssl x509 -pubkey -noout | \
  openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64

# Method 2: From certificate file
openssl x509 -in server.crt -pubkey -noout | \
  openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
```

Copy the base64 output and add to `local.properties`:

```properties
CERT_PIN_HASH=sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
```

> **Warning:** The `release` build variant **requires** a valid pin hash. The `debug` variant skips pinning entirely.

### 5.3 TURN Server Configuration

WebRTC requires a TURN server for NAT traversal when devices are on different networks.

**Option A: Self-hosted coturn (recommended for privacy)**

```bash
# On your VPS (Ubuntu 22.04)
sudo apt install coturn
```

Edit `/etc/turnserver.conf`:

```
listening-port=3478
fingerprint
lt-cred-mech
realm=turn.childhelper.com
user=childhelper:YOUR_SECURE_PASSWORD
```

```bash
sudo systemctl enable coturn && sudo systemctl start coturn
```

Add to `local.properties`:

```properties
TURN_SERVER_URL=turn:turn.childhelper.com:3478
TURN_USERNAME=childhelper
TURN_PASSWORD=YOUR_SECURE_PASSWORD
```

**Option B: Twilio TURN (managed)**

1. Sign up at [Twilio](https://www.twilio.com).
2. Create TURN credentials in the console.
3. Add the provided URLs, username, and password to `local.properties`.

**Option C: Offline mode (no TURN)**

Leave TURN fields empty. Local WebRTC will use **STUN only**, which works for devices on the same Wi-Fi network.

---

## 6. `local.properties` Template

Create `local.properties` in the **project root** (this file is `.gitignore`-ed and never committed):

```properties
# ============================================================
#  Privacy-First Child Helper — local.properties Template
#  Copy this file and fill in your actual values.
# ============================================================

# ---- Backend API ----
# Production backend
API_BASE_URL=https://api.childhelper.com

# Local development (uncomment as needed)
# API_BASE_URL=http://10.0.2.2:8080

# ---- SSL Certificate Pinning (release builds) ----
# Generate with: openssl s_client -connect api.childhelper.com:443 ...
CERT_PIN_HASH=sha256/ACTUAL_HASH_HERE

# ---- TURN Server (WebRTC NAT traversal) ----
TURN_SERVER_URL=turn:turn.childhelper.com:3478
TURN_USERNAME=childhelper
TURN_PASSWORD=YOUR_SECURE_PASSWORD

# ---- Development Overrides ----
# Skip certificate pinning in debug (true/false, default: true)
DEBUG_SKIP_PINNING=true

# Enable verbose WebRTC logging (true/false, default: false)
VERBOSE_WEBRTC=false

# Use local signaling instead of Firebase (true/false, default: false)
LOCAL_SIGNALING_ONLY=false
```

### Property Reference

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `API_BASE_URL` | Yes | — | Base URL of your backend API |
| `CERT_PIN_HASH` | For release | — | SHA-256 hash for SSL pinning |
| `TURN_SERVER_URL` | No | — | TURN server URI for WebRTC relay |
| `TURN_USERNAME` | No | — | TURN authentication username |
| `TURN_PASSWORD` | No | — | TURN authentication password |
| `DEBUG_SKIP_PINNING` | No | `true` | Disable pinning in debug builds |
| `VERBOSE_WEBRTC` | No | `false` | Enable detailed WebRTC logs |
| `LOCAL_SIGNALING_ONLY` | No | `false` | Use mDNS/local broadcast instead of Firebase |

---

## 7. Build Variants

The project defines two build types per app module:

| Variant | Optimizations | Logging | Certificate Pinning | Use Case |
|---------|--------------|---------|---------------------|----------|
| `debug` | None | Verbose (Logcat) | **Disabled** | Development & testing |
| `release` | R8 + ProGuard | Errors only | **Enforced** | Production deployment |

### Switching Variants in Android Studio

```
Build > Select Build Variant...
```

Choose `debug` or `release` for each module:

```
:app-child    → debug
:app-parent   → debug
```

### Building Release

```bash
# Generate signed release APKs
./gradlew assembleRelease

# Or with a specific keystore
./gradlew assembleRelease -Pandroid.injected.signing.store.file=release.jks \
  -Pandroid.injected.signing.store.password=KEYSTORE_PASSWORD \
  -Pandroid.injected.signing.key.alias=release \
  -Pandroid.injected.signing.key.password=KEY_PASSWORD
```

### `build.gradle` Snippet (Reference)

```kotlin
// app-child/build.gradle.kts (excerpt)
android {
    buildTypes {
        debug {
            buildConfigField("boolean", "ENABLE_LOGGING", "true")
            buildConfigField("boolean", "CERT_PINNING", "false")
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ENABLE_LOGGING", "false")
            buildConfigField("boolean", "CERT_PINNING", "true")
        }
    }
}
```

---

## 8. Running the App

### 8.1 Build & Deploy

```bash
# Build child APK
./gradlew :app-child:assembleDebug
adb -s <child-device-id> install app-child/build/outputs/apk/debug/app-child-debug.apk

# Build parent APK
./gradlew :app-parent:assembleDebug
adb -s <parent-device-id> install app-parent/build/outputs/apk/debug/app-parent-debug.apk
```

Or use **Android Studio**:
1. Select the `:app-child` run configuration.
2. Choose the child device from the device dropdown.
3. Click **Run** (Shift+F10).
4. Repeat for `:app-parent` on the parent device.

### 8.2 Device Pairing (QR Code)

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│   [Parent Device]              [Child Device]               │
│                                                             │
│   1. Open Parent App          1. Open Child App             │
│   2. Tap "Add Child"          2. Tap "Pair with Parent"     │
│   3. QR code appears          3. Scan QR code from Parent   │
│                                                             │
│         ┌─────────────┐                                     │
│         │  ┌───────┐  │         ┌─────────────┐             │
│         │  │ ▓▓▓▓▓ │  │  ───►  │  Scanning... │             │
│         │  │ ▓   ▓ │  │         └─────────────┘             │
│         │  │ ▓▓▓▓▓ │  │                                     │
│         │  └───────┘  │         Paired! ✅                   │
│         └─────────────┘                                     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Steps:**
1. On the **Parent device**, open the app and tap **"Add Child Device"**.
2. A **QR code** is generated containing a pairing token.
3. On the **Child device**, open the app and tap **"Pair with Parent"**.
4. Scan the QR code displayed on the parent device.
5. Both devices will show a **pairing confirmation** dialog — tap **Confirm** on both.
6. The devices are now linked via end-to-end encrypted signaling.

### 8.3 Test Detection & Calling

| Test Scenario | Steps | Expected Result |
|---------------|-------|-----------------|
| **Basic Detection** | Child app detects crying / "help" keyword | Parent receives push notification within 2–3 seconds |
| **Live Video Call** | Parent taps notification → "Start Video Call" | WebRTC connection establishes; live video/audio streams within 5 seconds |
| **Offline Mode** | Both devices on same Wi-Fi; disable mobile data | mDNS discovery works; signaling remains local |
| **Background Resilience** | Child app in background; trigger detection | MIUI/Doze mode handled; detection still fires via foreground service |

---

## 9. Recommended Test Devices

For thorough validation across the Android ecosystem, test on at least these device tiers:

### Tier 1: Baseline (Primary Development Target)

| Device | Android Version | RAM | Purpose |
|--------|----------------|-----|---------|
| **Xiaomi 12** | Android 12–14 (MIUI 13+) | 8 GB | Baseline reference device; validates MIUI-specific behavior (battery optimization, auto-start permissions, aggressive background killing) |

### Tier 2: Target SDK Validation

| Device | Android Version | RAM | Purpose |
|--------|----------------|-----|---------|
| **Google Pixel 7/8** | Android 14–16 | 8 GB | Validates latest Android APIs, granular media permissions, and foreground service restrictions |
| **Samsung Galaxy S23** | Android 13–14 (One UI) | 8 GB | Validates Samsung-specific permission dialogs and battery optimization |

### Tier 3: Low-End / Legacy

| Device | Android Version | RAM | Purpose |
|--------|----------------|-----|---------|
| **Xiaomi Redmi 9A / Nokia C20** | Android 8–11 | 3–4 GB | Validates performance on constrained hardware; ensures WebRTC and ML inference do not cause ANRs |

### Minimum Supported Configuration

```
Android API 26+ (Android 8.0 Oreo)
3 GB RAM
Camera + Microphone hardware
ARM64 processor
```

---

## 10. Troubleshooting

### Gradle Sync Failures

| Symptom | Cause | Solution |
|---------|-------|----------|
| `Could not resolve com.google.firebase:...` | Missing `google-services.json` or no internet | Place `google-services.json` in both `app-child/` and `app-parent/`; check network |
| `Minimum supported Gradle version is 8.x` | Wrong Gradle wrapper | Run `./gradlew wrapper --gradle-version 8.9` |
| `NDK not configured` | Native WebRTC requires NDK | Open SDK Manager > SDK Tools > install NDK |

```bash
# Nuclear option: clean everything
./gradlew cleanBuildCache
rm -rf ~/.gradle/caches/
./gradlew sync
```

### Compose Compiler Errors

| Symptom | Cause | Solution |
|---------|-------|----------|
| `Compose compiler version mismatch` | Kotlin version incompatible with Compose compiler | Check `libs.versions.toml` — ensure `kotlin = "2.0.x"` matches `composeCompiler = "1.5.x"` |
| `@Composable` functions not found | Missing Compose dependencies | Verify `implementation(libs.androidx.compose.ui)` in `build.gradle.kts` |

```kotlin
// In build.gradle.kts — verify this block exists
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

### Firebase Configuration Issues

| Symptom | Cause | Solution |
|---------|-------|----------|
| `FirebaseApp is not initialized` | Missing or misnamed `google-services.json` | Verify file is at correct path; package name must match exactly |
| `API key expired` | Debug key mismatch | Add debug SHA-1/SHA-256 to Firebase Console > Project Settings |
| FCM messages not received | Device registration failed | Check `FirebaseMessaging.getInstance().token` in logs; verify Server Key on backend |

### WebRTC Connection Failures

| Symptom | Cause | Solution |
|---------|-------|----------|
| `ICE connection failed` | TURN server unreachable or misconfigured | Verify TURN credentials in `local.properties`; test with `turnutils_uclient` |
| `No video stream` | Camera permission denied or busy | Grant Camera permission in Settings; ensure no other app is using the camera |
| `SDP offer/answer timeout` | Signaling server unreachable | Check `API_BASE_URL` in `local.properties`; verify network connectivity |
| Works on Wi-Fi but not on mobile data | Missing TURN relay | TURN server is mandatory for cross-network connections; verify STUN+TURN are both configured |

```bash
# Test TURN server connectivity
turnutils_uclient -u childhelper -w YOUR_PASSWORD turn.childhelper.com
```

### MIUI Battery Optimization

MIUI aggressively kills background apps, which breaks child-side detection.

**Required settings on the Child device (Xiaomi/Redmi):**

1. **Settings > Apps > Child Helper > Battery Saver > No restrictions**
2. **Settings > Apps > Permissions > Auto-start > Enable** for Child Helper
3. **Settings > Notification > Child Helper > Allow all notifications**
4. **Settings > Privacy > Special permissions > Display pop-up windows > Allow**
5. **Recent Apps > Long-press Child Helper > Lock** (prevents swipe-to-kill)

> **Programmatic workaround:** The app includes a `BatteryOptimizationHelper` that guides the user through these steps on first launch.

### Camera Permission Issues on Android 14+ (API 34+)

Android 14 introduces **partial camera access** and stricter foreground service requirements.

| Symptom | Cause | Solution |
|---------|-------|----------|
| `CameraAccessException: CAMERA_DISABLED` | Foreground service not declared properly | Ensure `ForegroundServicePermission` is declared in manifest |
| Camera permission dialog shows "Select photos" | Partial media permission selected | Request `CAMERA` + `RECORD_AUDIO` permissions together; handle `shouldShowRequestPermissionRationale()` |

```kotlin
// Correct permission request for Android 14+
val permissions = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.FOREGROUND_SERVICE_CAMERA  // API 34+
)
ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE)
```

### General Diagnostic Commands

```bash
# Check connected devices
adb devices -l

# View app logs
adb logcat -s "ChildHelper:*" "WebRTC:*" "FirebaseMessaging:*"

# Check app permissions on device
adb shell dumpsys package com.childhelper.child | grep permission

# Force-stop and restart
adb shell am force-stop com.childhelper.child
adb shell am start -n com.childhelper.child/.MainActivity

# Dump network state (check WebRTC ICE candidates)
adb shell dumpsys connectivity | grep -i ice
```

---

## Quick Start Checklist

- [ ] JDK 17 installed and `java -version` shows 17.x
- [ ] Android Studio Ladybug+ with SDK API 36
- [ ] `ANDROID_HOME` and `JAVA_HOME` set
- [ ] Repository cloned and Gradle sync successful
- [ ] `google-services.json` placed in `app-child/` and `app-parent/`
- [ ] `local.properties` created with `API_BASE_URL`
- [ ] `CERT_PIN_HASH` generated (for release builds)
- [ ] TURN server configured (or using local-signaling mode)
- [ ] Both APKs built successfully (`./gradlew assembleDebug`)
- [ ] Child app deployed to child's device
- [ ] Parent app deployed to parent's device
- [ ] Devices paired via QR code
- [ ] Detection and calling tested end-to-end

---

## Need Help?

- **Open an issue:** [GitHub Issues](https://github.com/your-org/privacy-first-child-helper/issues)
- **Discussions:** [GitHub Discussions](https://github.com/your-org/privacy-first-child-helper/discussions)
- **Architecture docs:** See [`ARCHITECTURE.md`](./ARCHITECTURE.md)
- **API reference:** See [`API.md`](./API.md)
