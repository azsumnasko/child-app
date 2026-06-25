@echo off
REM ========================================
REM Child Helper App — Quick Start Script
REM Run this every time devices reconnect
REM ========================================

set ADB=C:\Users\Nasko\AppData\Local\Android\Sdk\platform-tools\adb.exe

echo === Step 1: Check devices ===
%ADB% devices -l

echo === Step 2: Set up reverse tunnels ===
%ADB% -s 386c9711 reverse tcp:8080 tcp:8080
%ADB% -s RZCX426L2BL reverse tcp:8080 tcp:8080

echo === Step 3: Verify tunnels ===
%ADB% -s 386c9711 reverse --list
%ADB% -s RZCX426L2BL reverse --list

echo === Step 4: Grant permissions (if needed) ===
%ADB% -s 386c9711 shell pm grant com.childhelper.app.child android.permission.CAMERA 2>nul
%ADB% -s 386c9711 shell pm grant com.childhelper.app.child android.permission.RECORD_AUDIO 2>nul
%ADB% -s 386c9711 shell pm grant com.childhelper.app.child android.permission.POST_NOTIFICATIONS 2>nul
%ADB% -s RZCX426L2BL shell pm grant com.childhelper.app.parent android.permission.CAMERA 2>nul
%ADB% -s RZCX426L2BL shell pm grant com.childhelper.app.parent android.permission.POST_NOTIFICATIONS 2>nul

echo === Step 5: Launch apps ===
%ADB% -s 386c9711 shell am start -n com.childhelper.app.child/.ui.home.ChildHomeActivity
%ADB% -s RZCX426L2BL shell am start -n com.childhelper.app.parent/.ui.dashboard.ParentDashboardActivity

echo === Ready! Open the apps on both devices ===
echo === Pair via QR, then tap Live View on Samsung ===
pause
