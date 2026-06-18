# Build Commands

Privacy-First Child Helper Android App - Build Reference

---

## Quick Start

### Prerequisites

- Android SDK installed (API 36 recommended, minimum API 26 for `compileSdk`)
- JDK 17 or later
- `local.properties` created with `sdk.dir` set
- Gradle wrapper JAR downloaded (see First-Time Setup)

---

## First-Time Setup

1. **Copy the local properties template:**

   ```bash
   cp local.properties.template local.properties
   ```

2. **Edit `local.properties` and set your Android SDK path:**

   - **Linux:** `sdk.dir=/home/<user>/Android/Sdk`
   - **macOS:** `sdk.dir=/Users/<user>/Library/Android/sdk`
   - **Windows:** `sdk.dir=C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk`

3. **Download the Gradle wrapper JAR:**

   - **Windows:**
     ```cmd
     cd gradle\wrapper
     download-wrapper.bat
     ```

   - **macOS / Linux:**
     ```bash
     sh gradle/wrapper/download-wrapper.sh
     ```

4. **Build the project:**

   ```bash
   ./gradlew assembleDebug
   ```

---

## Windows

```cmd
:: Set your Android SDK path in local.properties first
:: Download wrapper JAR: gradle\wrapper\download-wrapper.bat

:: Child App - Debug
gradlew.bat :app:child:assembleDebug

:: Child App - Release
gradlew.bat :app:child:assembleRelease

:: Parent App - Debug
gradlew.bat :app:parent:assembleDebug

:: Parent App - Release
gradlew.bat :app:parent:assembleRelease

:: Or build all at once
gradlew.bat assembleDebug
gradlew.bat assembleRelease
```

---

## macOS / Linux

```bash
# Set your Android SDK path in local.properties first
# Download wrapper JAR: sh gradle/wrapper/download-wrapper.sh

# Child App - Debug
./gradlew :app:child:assembleDebug

# Child App - Release
./gradlew :app:child:assembleRelease

# Parent App - Debug
./gradlew :app:parent:assembleDebug

# Parent App - Release
./gradlew :app:parent:assembleRelease

# Or build all at once
./gradlew assembleDebug
./gradlew assembleRelease
```

---

## Output Locations

| Variant   | Child APK                                              | Parent APK                                               |
|-----------|--------------------------------------------------------|----------------------------------------------------------|
| **Debug** | `app/child/build/outputs/apk/debug/`   | `app/parent/build/outputs/apk/debug/`    |
| **Release** | `app/child/build/outputs/apk/release/` | `app/parent/build/outputs/apk/release/` |

### APK Filenames

| App    | Debug APK Name             | Release APK Name             |
|--------|----------------------------|------------------------------|
| Child  | `child-debug.apk`          | `child-release.apk`          |
| Parent | `parent-debug.apk`         | `parent-release.apk`         |

---

## Optional Release Signing

For signed release builds, configure these properties in `local.properties`:

```properties
RELEASE_STORE_FILE=/path/to/your/release.keystore
RELEASE_STORE_PASSWORD=your_keystore_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

---

## Optional Firebase Setup

If using Firebase Cloud Messaging:

1. Create Firebase projects for both apps at https://console.firebase.google.com
2. Download `google-services.json` for each app:
   - Place child's JSON at: `app/child/google-services.json`
   - Place parent's JSON at: `app/parent/google-services.json`
3. The `google-services.json` file is gitignored for security

---

## Troubleshooting

| Issue                              | Solution                                                      |
|------------------------------------|---------------------------------------------------------------|
| `gradlew not found`                | Run `gradle/wrapper/download-wrapper.sh` (or `.bat`) first    |
| `SDK location not found`           | Ensure `local.properties` exists with `sdk.dir` set           |
| `Permission denied` (gradlew)      | Run `chmod +x gradlew`                                        |
| `Build fails with memory error`    | Increase heap in `gradle.properties` (already set to 8192m)   |
| `R8/ProGuard warnings`             | Check `proguard-rules.pro` for missing `-dontwarn` rules      |
| `Signing config missing`           | Set signing properties in `local.properties` (see above)      |
| `Firebase not initialized`         | Add `google-services.json` to each app module (optional)      |

---

## Project Module Structure

```
ChildHelper (root)
|-- app:child       (Child APK - cry/motion detection, SOS, calling)
|-- app:parent      (Parent APK - dashboard, alert history, live view)
|-- core:common     (Shared models, utilities, events)
|-- core:security   (Encryption, keystore, secure preferences)
|-- core:network    (WebRTC signaling, FCM, Retrofit APIs)
```
