# Building on Windows

This guide covers everything you need to build the Privacy-First Child Helper Android app on Windows.

---

## Prerequisites

| Tool | Minimum Version | Download Link |
|------|----------------|---------------|
| Android Studio | Ladybug (2024.2) | https://developer.android.com/studio |
| JDK | 17 (Temurin recommended) | https://adoptium.net/temurin/releases/?version=17 |
| Git for Windows | 2.40+ | https://git-scm.com/download/win |

### Optional but Recommended

- **Git for Windows** includes Git Bash, which provides a Unix-like shell on Windows. This is useful if you prefer `./gradlew` over `gradlew.bat`.
- Enable **Windows Developer Mode** if you plan to use symlinks (Settings > Update & Security > For developers > Developer Mode).

---

## Setting ANDROID_HOME

The build requires the `ANDROID_HOME` environment variable to point to your Android SDK.

### Method 1: System Environment Variables

1. Open **Settings > System > About > Advanced system settings**
2. Click **Environment Variables**
3. Under **User variables**, click **New**
4. Set:
   - **Variable name**: `ANDROID_HOME`
   - **Variable value**: `C:\Users\<YourName>\AppData\Local\Android\Sdk`
5. Click **OK** on all dialogs
6. Restart any open terminals/IDEs

### Method 2: Via Android Studio

Android Studio automatically sets this when you open the project, but you should verify:

1. Open Android Studio
2. **File > Settings > Appearance & Behavior > System Settings > Android SDK**
3. Copy the **Android SDK Location** path
4. Set it as `ANDROID_HOME` using Method 1

### Method 3: In local.properties

Create a `local.properties` file in the project root:

```properties
sdk.dir=C:\\Users\\<YourName>\\AppData\\Local\\Android\\Sdk
```

> **Note**: Use double backslashes (`\\`) or forward slashes (`/`) in `local.properties`.

---

## Setting JAVA_HOME

The project requires JDK 17. Set `JAVA_HOME`:

```powershell
# Check your installed JDKs
Get-ChildItem "C:\Program Files\Eclipse Adoptium"

# Set JAVA_HOME (run in PowerShell as Admin, or set via System Properties)
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-17", "User")
```

Verify:
```cmd
%JAVA_HOME%\bin\java -version
```

---

## Building the Project

### Step 1: Download the Gradle Wrapper JAR

This repository does not include `gradle-wrapper.jar`. You must download it first:

**Using the provided batch script:**
```cmd
gradle\wrapper\download-wrapper.bat
```

**Using PowerShell:**
```powershell
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar" -OutFile "gradle\wrapper\gradle-wrapper.jar"
```

**Manual download:**
1. Download from: https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar
2. Save to: `gradle\wrapper\gradle-wrapper.jar`

### Step 2: Create local.properties

```cmd
copy local.properties.template local.properties
```

Edit `local.properties` and set your Android SDK path:
```properties
sdk.dir=C:\Users\<YourName>\AppData\Local\Android\Sdk
API_BASE_URL=https://api.childhelper.com
CERT_PIN_HASH=sha256/YOUR_CERT_HASH_HERE
```

### Step 3: Build

**Using gradlew.bat (recommended on Windows):**
```cmd
gradlew.bat assembleDebug
```

**Using PowerShell:**
```powershell
.\gradlew.bat assembleDebug
```

> **Do NOT use `./gradlew` on Windows** unless you are in Git Bash or WSL. The `./gradlew` syntax is for Unix shells only.

---

## PowerShell vs CMD Considerations

### CMD (Command Prompt)
- Works with `gradlew.bat` directly
- No special syntax needed
- Example: `gradlew.bat assembleDebug`

### PowerShell
- Prefix with `.\` for execution: `.\gradlew.bat assembleDebug`
- If you get execution policy errors, run:
  ```powershell
  Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
  ```
- Download commands work natively with `Invoke-WebRequest`

### Git Bash (included with Git for Windows)
- Supports `./gradlew` (the Unix wrapper script)
- Handles Unix-style paths
- May require `winpty` for interactive Gradle tasks

### WSL (Windows Subsystem for Linux)
- Full Linux environment — use `./gradlew`
- Make sure Android SDK is accessible from WSL path (e.g., `/mnt/c/...`)
- Set `sdk.dir` in `local.properties` to the WSL-mounted path

---

## Common Windows Build Issues

### Issue 1: `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`

**Cause**: `gradle-wrapper.jar` is missing.

**Fix**: Run `gradle\wrapper\download-wrapper.bat` or download manually.

### Issue 2: `JAVA_HOME is set to an invalid directory`

**Cause**: `JAVA_HOME` points to a JRE instead of JDK, or the path contains spaces without quotes.

**Fix**: Set `JAVA_HOME` to the JDK root directory (the one containing `bin\javac.exe`).

### Issue 3: Long path errors (error code 206)

**Cause**: Windows has a 260-character path limit (MAX_PATH).

**Fix**:
1. Move the project to a short path, e.g., `C:\ChildHelper\`
2. Or enable long path support in Windows 10+:
   - Group Policy: `Computer Configuration > Administrative Templates > System > Filesystem > Enable Win32 long paths`
   - Or registry: `HKLM\SYSTEM\CurrentControlSet\Control\FileSystem\LongPathsEnabled = 1`

### Issue 4: `SSL peer shut down incorrectly` during Gradle sync

**Cause**: Corporate proxy or firewall interfering with HTTPS.

**Fix**: In `gradle.properties`, add:
```properties
systemProp.http.proxyHost=proxy.company.com
systemProp.http.proxyPort=8080
systemProp.https.proxyHost=proxy.company.com
systemProp.https.proxyPort=8080
```

### Issue 5: `Daemon will be stopped at the end of the build`

**Cause**: Gradle daemon memory issues on Windows.

**Fix**: In `gradle.properties`, reduce heap or disable daemon:
```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.daemon=false
```

### Issue 6: Antivirus scanning locks files during build

**Cause**: Windows Defender or third-party AV locks Gradle cache files.

**Fix**: Add exclusions for:
- `C:\Users\<YourName>\.gradle`
- Your project directory
- `C:\Users\<YourName>\AppData\Local\Android\Sdk`

### Issue 7: Line ending issues (file not found errors in batch scripts)

**Cause**: Git may have converted `gradlew.bat` to LF line endings.

**Fix**: Configure Git to handle line endings correctly:
```cmd
git config --global core.autocrlf true
```
Then re-clone the repository.

---

## Verification Checklist

Before your first build, verify:

- [ ] `gradle-wrapper.jar` exists in `gradle\wrapper\`
- [ ] `local.properties` exists with correct `sdk.dir`
- [ ] `JAVA_HOME` is set to JDK 17
- [ ] `ANDROID_HOME` is set (or defined in `local.properties`)
- [ ] Android Studio is installed with SDK Platform 36 and Build Tools
- [ ] Project path is short (under 100 characters recommended)

---

## Building from Android Studio

1. **File > Open** and select the project folder
2. Android Studio will prompt to sync Gradle — click **Sync Now**
3. If `gradle-wrapper.jar` is missing, Android Studio may auto-download it, or you may need to download it manually first
4. **Build > Make Project** (Ctrl+F9)
5. To run: Select `app:child` or `app:parent` from the run configuration dropdown, then click **Run** (Shift+F10)

---

## Quick Reference: Build Commands

```cmd
# Debug APKs
gradlew.bat :app:child:assembleDebug
gradlew.bat :app:parent:assembleDebug

# Release APKs (requires signing config)
gradlew.bat :app:child:assembleRelease
gradlew.bat :app:parent:assembleRelease

# Run tests
gradlew.bat test

# Clean build
gradlew.bat clean

# Full clean + build
gradlew.bat clean assembleDebug
```
