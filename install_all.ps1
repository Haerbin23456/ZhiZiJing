$ErrorActionPreference = "Stop"

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = ".\app\build\outputs\apk\debug\app-debug.apk"

if (!(Test-Path $adb)) {
    Write-Host "Cannot find adb.exe at: $adb"
    Write-Host "Please check Android Studio SDK Location."
    exit 1
}

Write-Host "Building APK..."
.\gradlew.bat :app:assembleDebug

if (!(Test-Path $apk)) {
    Write-Host "Cannot find APK at: $apk"
    exit 1
}

Write-Host "Finding connected physical devices..."

$devices = & $adb devices |
    Select-String "`tdevice$" |
    ForEach-Object { ($_ -split "\s+")[0] } |
    Where-Object { $_ -notlike "emulator-*" }

if ($devices.Count -eq 0) {
    Write-Host "No physical devices found."
    exit 1
}

foreach ($d in $devices) {
    Write-Host "Installing to $d ..."
    & $adb -s $d install -r -t $apk
}

Write-Host "Done."