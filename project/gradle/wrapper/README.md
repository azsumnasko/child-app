# Gradle Wrapper

The `gradle-wrapper.jar` is not included in this repository. This is a common
practice for repositories generated from templates or when the JAR cannot be
distributed directly.

## Quick Fix

### Option 1: Use the Download Script

**On Linux/macOS:**
```bash
sh gradle/wrapper/download-wrapper.sh
```

**On Windows (CMD):**
```cmd
gradle\wrapper\download-wrapper.bat
```

**On Windows (PowerShell):**
```powershell
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar" -OutFile "gradle\wrapper\gradle-wrapper.jar"
```

### Option 2: Manual Download

1. Download the wrapper JAR directly from:
   https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar

2. Save it to: `gradle/wrapper/gradle-wrapper.jar`

### Option 3: Use an Existing Gradle Installation

If you already have Gradle installed locally:

```bash
# Linux/macOS
gradle wrapper --gradle-version 8.9

# Windows
gradle wrapper --gradle-version 8.9
```

## Verification

After downloading, verify the wrapper works:

```bash
# Linux/macOS
./gradlew --version

# Windows
gradlew.bat --version
```

You should see Gradle 8.9 in the output.

## Troubleshooting

**"Could not find or load main class org.gradle.wrapper.GradleWrapperMain"**
- This means `gradle-wrapper.jar` is missing. Follow the steps above.

**SSL certificate errors during download**
- Some corporate networks intercept SSL traffic. Try downloading via browser.
- Or use: `curl -L -k -o gradle-wrapper.jar <url>` (Unix, insecure mode)

**Permission denied on `gradlew`**
- Run: `chmod +x gradlew` (Unix/macOS only; not needed on Windows)
