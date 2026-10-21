# SehatSethu - Local Run Script
$ErrorActionPreference = "Stop"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "   SehatSethu - Local App Launcher" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Check Backend Services
Write-Host "`n[1/3] Checking XAMPP Apache and MySQL..." -ForegroundColor Yellow
$httpd = Get-Process -Name "httpd" -ErrorAction SilentlyContinue
$mysqld = Get-Process -Name "mysqld" -ErrorAction SilentlyContinue

if (-not $httpd) {
    Write-Host "Starting Apache..." -ForegroundColor Gray
    Start-Process "C:\xampp\apache_start.bat"
}
if (-not $mysqld) {
    Write-Host "Starting MySQL..." -ForegroundColor Gray
    Start-Process "C:\xampp\mysql_start.bat"
}

# Verify Health
try {
    $res = Invoke-RestMethod -Uri "http://127.0.0.1/sehatsethu_api/api/health.php" -Method Get -TimeoutSec 5
    if ($res.ok) {
        Write-Host "✓ Backend API is healthy and reachable at http://127.0.0.1/sehatsethu_api/api/" -ForegroundColor Green
    }
} catch {
    Write-Warning "Could not verify http://127.0.0.1/sehatsethu_api/api/health.php. Please ensure Apache is running in XAMPP."
}

# 2. Check ADB & Device
Write-Host "`n[2/3] Checking connected Android devices / emulators..." -ForegroundColor Yellow
$adb = "C:\Users\SSE\AppData\Local\Android\Sdk\platform-tools\adb.exe"

$devices = & $adb devices | Select-String -Pattern "device$"
if (-not $devices) {
    Write-Host "⚠ No active device or emulator detected by ADB." -ForegroundColor Red
    Write-Host "Please do one of the following:" -ForegroundColor White
    Write-Host "  a) Connect your Android phone via USB and enable USB Debugging." -ForegroundColor Gray
    Write-Host "  b) Or start an emulator from Android Studio's Device Manager." -ForegroundColor Gray
    Write-Host "`nWaiting for device to connect (press Ctrl+C to cancel)..." -ForegroundColor Yellow
    
    & $adb wait-for-device
    Write-Host "✓ Device detected!" -ForegroundColor Green
} else {
    Write-Host "✓ Android device/emulator detected." -ForegroundColor Green
}

# 3. Port Forwarding
Write-Host "`n[3/3] Setting up port forwarding & deploying app..." -ForegroundColor Yellow
& $adb reverse tcp:80 tcp:80
& $adb reverse tcp:8443 tcp:8443
Write-Host "✓ Forwarded tcp:80 and tcp:8443 to device." -ForegroundColor Green

# 4. Install & Launch APK
$apk = "c:\Users\SSE\Desktop\sehat sethu\SehatSethu-HealthCare-Within-Reach-FRONTEND--main\SehatSethu-HealthCare-Within-Reach-FRONTEND--main\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    Write-Host "Installing APK ($apk)..." -ForegroundColor Gray
    & $adb install -r $apk
    Write-Host "Launching SehatSethu app..." -ForegroundColor Green
    & $adb shell am start -n com.simats.sehatsethu/.SplashActivity
    Write-Host "`n✓ App successfully started!" -ForegroundColor Green
} else {
    Write-Warning "APK not found at $apk. Please run assembleDebug first."
}
