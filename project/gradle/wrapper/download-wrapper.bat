@echo off
REM Gradle Wrapper JAR Download Script (Windows)
REM ==============================================
REM The gradle-wrapper.jar is intentionally excluded from this repository.
REM This script downloads the official wrapper JAR from the Gradle GitHub repository.
REM
REM Usage:
REM   cd gradle\wrapper
REM   download-wrapper.bat
REM
REM Or from project root:
REM   gradle\wrapper\download-wrapper.bat

setlocal enabledelayedexpansion

set "WRAPPER_DIR=%~dp0"
set "JAR_URL=https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
set "JAR_FILE=%WRAPPER_DIR%gradle-wrapper.jar"

echo Downloading gradle-wrapper.jar...
echo   URL: %JAR_URL%
echo   Destination: %JAR_FILE%

REM Try PowerShell first (available on Windows 7+)
powershell -Command "try { Invoke-WebRequest -Uri '%JAR_URL%' -OutFile '%JAR_FILE%' -UseBasicParsing; exit 0 } catch { exit 1 }" >nul 2>&1
if %ERRORLEVEL% == 0 goto verify

REM Fallback to bitsadmin (older Windows)
bitsadmin /transfer "GradleWrapperDownload" "%JAR_URL%" "%JAR_FILE%" >nul 2>&1
if %ERRORLEVEL% == 0 goto verify

REM Fallback to certutil
certutil -urlcache -split -f "%JAR_URL%" "%JAR_FILE%" >nul 2>&1
if %ERRORLEVEL% == 0 goto verify

echo ERROR: Failed to download. No supported download tool found.
echo Please download manually from:
echo   %JAR_URL%
echo And save it to:
echo   %JAR_FILE%
exit /b 1

:verify
if exist "%JAR_FILE%" (
    echo SUCCESS: gradle-wrapper.jar downloaded successfully.
    dir "%JAR_FILE%"
    exit /b 0
) else (
    echo ERROR: Download failed. Please download manually from:
    echo   %JAR_URL%
    exit /b 1
)
