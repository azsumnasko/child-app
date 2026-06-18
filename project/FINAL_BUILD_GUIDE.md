# Final Build Guide — Windows

> **Iteration 17: Final APK Build Verification**
> This guide was generated after a complete end-to-end verification of all build artifacts, configurations, and Windows-specific setup.

---

## Prerequisites

| Component | Version | Notes |
|-----------|---------|-------|
| Windows | 10/11 | 64-bit required |
| Android Studio | Ladybug (2024.2.1) or newer | Install via [developer.android.com/studio](https://developer.android.com/studio) |
| JDK | 17 (Temurin/Adoptium recommended) | Download from [adoptium.net](https://adoptium.net/temurin/releases/?version=17) |
| Android SDK | API 26-36 | Install via Android Studio SDK Manager |
| Git for Windows | 2.40+ | Download from [git-scm.com/download/win](https://git-scm.com/download/win) |

---

## Step 1: Environment Setup

### Install JDK 17

1. Download and run the Temurin JDK 17 MSI installer.
2. Choose **Entire feature will be installed on local hard drive** for all components.
3. Let the installer set `JAVA_HOME` automatically, or set it manually (see below).

### Set Environment Variables

Open **Settings > System > About > Advanced system settings > Environment Variables**.

Under **User variables**, add or update:

| Variable | Value |
|----------|-------|
| `JAVA_HOME` | `C:\Program Files\Eclipse Adoptium\jdk-17` |
| `ANDROID_HOME` | `C:\Users\YOUR_USERNAME\AppData\Local\Android\Sdk` |

Then edit `Path` (User variables) and add these entries:

```
%JAVA_HOME%\bin
%ANDROID_HOME%\platform-tools
```

> **Verify:** Open a new Command Prompt and run:
> ```cmd
> java -version
> adb --version
> ```
> Both should print version info without errors.

---

## Step 2: Project Setup

### Clone the Repository

```cmd
git clone <repo-url> ChildHelper
cd ChildHelper
```

> **Tip:** Place the project in a short path like `C:\ChildHelper\` to avoid Windows MAX_PATH issues.

### Create local.properties

```cmd
copy local.properties.template local.properties
```

Edit `local.properties` with your actual Android SDK path (use double backslashes):

```properties
sdk.dir=C:\\Users\\YOUR_USERNAME\\AppData\\Local\\Android\\Sdk
```

> **Important:** `local.properties` is already listed in `.gitignore` and must NEVER be committed.

---

## Step 3: Download Gradle Wrapper

The `gradle-wrapper.jar` is intentionally excluded from this repository. Download it using the provided script:

```cmd
.\gradle\wrapper\download-wrapper.bat
```

This script tries multiple methods in order:
1. PowerShell `Invoke-WebRequest`
2. `bitsadmin` (older Windows)
3. `certutil`

If all fail, download manually from:
- URL: `https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar`
- Save to: `gradle\wrapper\gradle-wrapper.jar`

> **Verify:** Check that `gradle\wrapper\gradle-wrapper.jar` now exists (approx. 60 KB).

---

## Step 4: Build Debug APKs

### Child App (Debug)

```cmd
.\gradlew.bat :app:child:assembleDebug
```

### Parent App (Debug)

```cmd
.\gradlew.bat :app:parent:assembleDebug
```

### Build Both at Once

```cmd
.\gradlew.bat assembleDebug
```

> **First build** will download Gradle 8.9 and all dependencies. This can take 10-30 minutes depending on your connection. Subsequent builds are much faster.

---

## Step 5: Locate APKs

After a successful build, the APKs are located at:

| App | Variant | Path |
|-----|---------|------|
| **Child** | Debug | `app\child\build\outputs\apk\debug\child-debug.apk` |
| **Child** | Release | `app\child\build\outputs\apk\release\child-release.apk` |
| **Parent** | Debug | `app\parent\build\outputs\apk\debug\parent-debug.apk` |
| **Parent** | Release | `app\parent\build\outputs\apk\release\parent-release.apk` |

---

## Step 6: Install on Device

### Enable Developer Options on Your Android Device

1. Go to **Settings > About phone**
2. Tap **Build number** 7 times
3. Go back to **Settings > System > Developer options**
4. Enable **USB debugging**

### Connect and Install

```cmd
adb devices
```

You should see your device listed. Then install:

```cmd
adb install app\child\build\outputs\apk\debug\child-debug.apk
adb install app\parent\build\outputs\apk\debug\parent-debug.apk
```

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `JAVA_HOME not found` | Check JDK 17 is installed, the `JAVA_HOME` env var is set, and `%JAVA_HOME%\bin` is in `Path`. Restart your terminal after making changes. |
| `SDK not found` | Check `local.properties` has correct `sdk.dir` with double backslashes (`\\`). |
| `Gradle wrapper not found` | Run `.\gradle\wrapper\download-wrapper.bat`. Verify `gradle\wrapper\gradle-wrapper.jar` exists. |
| `Could not find or load main class org.gradle.wrapper.GradleWrapperMain` | The wrapper JAR is missing or corrupt. Re-run the download script. |
| Out of memory / `Java heap space` | Edit `gradle.properties`: increase `org.gradle.jvmargs=-Xmx12g` (or reduce to `-Xmx4096m` on low-RAM systems). |
| Compose compiler error | Check `local.properties` does not contain invalid entries or stray characters. |
| Build too slow | Add `org.gradle.parallel=true` and `org.gradle.caching=true` to `gradle.properties`. |
| Long path errors (error 206) | Move project to a short path like `C:\ChildHelper\` or enable Win32 long paths in Group Policy. |
| SSL errors during sync | Corporate proxy may interfere. Add proxy settings to `gradle.properties` (see `docs/WINDOWS_BUILD.md`). |
| Antivirus scanning locks files | Add exclusions for `.gradle`, your project directory, and the Android SDK. |
| Line ending issues | Run `git config --global core.autocrlf true` and re-clone if batch scripts fail mysteriously. |

---

## Release Build (Optional)

Release builds require a signing keystore. Generate one:

```cmd
keytool -genkey -v -keystore release.keystore -alias childhelper -keyalg RSA -keysize 2048 -validity 10000
```

Then add to `local.properties`:

```properties
RELEASE_STORE_FILE=C:\\path\\to\\your\\release.keystore
RELEASE_STORE_PASSWORD=your_keystore_password
RELEASE_KEY_ALIAS=childhelper
RELEASE_KEY_PASSWORD=your_key_password
```

Build release APKs:

```cmd
.\gradlew.bat :app:child:assembleRelease
.\gradlew.bat :app:parent:assembleRelease
```

> **Warning:** Keep your keystore and passwords secure. Losing them means you cannot update your app on the Play Store.

---

## Quick Reference Card

```cmd
:: Setup (one-time)
copy local.properties.template local.properties
.\gradle\wrapper\download-wrapper.bat

:: Debug builds
.\gradlew.bat :app:child:assembleDebug
.\gradlew.bat :app:parent:assembleDebug

:: Release builds
.\gradlew.bat :app:child:assembleRelease
.\gradlew.bat :app:parent:assembleRelease

:: Clean everything
.\gradlew.bat clean

:: Run tests
.\gradlew.bat test
```

---

## Verified Build Configuration (Iteration 17)

The following was verified during final inspection:

| Check | Status | Details |
|-------|--------|---------|
| `gradlew.bat` | ✅ | Exists with CRLF line endings |
| `gradle-wrapper.properties` | ✅ | Uses `gradle-8.9-bin.zip` |
| `download-wrapper.bat` | ✅ | PowerShell + bitsadmin + certutil fallbacks |
| `local.properties.template` | ✅ | Windows path template + signing placeholders |
| `docs/WINDOWS_BUILD.md` | ✅ | Comprehensive Windows-specific guide |
| `BUILD_COMMANDS.md` | ✅ | Copy-paste commands for all platforms |
| `app/child/build.gradle.kts` | ✅ | signingConfigs, buildTypes, proguard, packaging, buildConfig |
| `app/parent/build.gradle.kts` | ✅ | signingConfigs, buildTypes, proguard, packaging, buildConfig |
| `proguard-rules.pro` (child) | ✅ | 2556 bytes |
| `proguard-rules.pro` (parent) | ✅ | 2631 bytes |
| Debug APK output paths | ✅ | `app/child/build/outputs/apk/debug/`, `app/parent/build/outputs/apk/debug/` |
| Release APK output paths | ✅ | `app/child/build/outputs/apk/release/`, `app/parent/build/outputs/apk/release/` |

---

*Generated: Final verification complete. Project ready for Windows handoff.*
