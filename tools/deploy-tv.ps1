# Puts the release build on the living-room TV without anyone at the TV:
#   powershell -ExecutionPolicy Bypass -File tools\deploy-tv.ps1          update (keeps login, favourites, searches)
#   powershell -ExecutionPolicy Bypass -File tools\deploy-tv.ps1 -Login   first install: also log in from %USERPROFILE%\.kukatko.env
# Connects with Home Assistant's ADB key (C:\path\to\adbkey, already trusted by the TV), so no
# "Allow debugging?" prompt appears. Refuses while the TV screen is on (someone is watching) unless -Force.
# The app is closed again afterwards; a login with the screen off never starts playback.
param([switch]$Login, [switch]$Force, [switch]$NoBuild, [string]$Tv = "192.168.0.50:5555")
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot
$adb = "C:\Users\you\android-dev\sdk\platform-tools\adb.exe"
$apk = "$root\app\build\outputs\apk\release\app-release.apk"
$haKey = "C:\path\to\adbkey"

if (-not $NoBuild) {
    Write-Host "=== Building release"
    $env:JAVA_HOME = "C:\Users\you\android-dev\jdk17"
    & "$root\gradlew.bat" -p $root assembleRelease --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}

Write-Host "=== Connecting to $Tv"
if (Test-Path $haKey) {
    # The adb server reads extra keys only when it starts.
    & $adb kill-server 2>$null
    $env:ADB_VENDOR_KEYS = $haKey
} else {
    Write-Host "    $haKey not found: the TV will ask 'Allow debugging?' once; tick 'Always allow'."
}
& $adb connect $Tv | Write-Host
$env:ANDROID_SERIAL = $Tv
if ((& $adb get-state 2>$null) -ne "device") { throw "TV $Tv is not reachable over ADB (is it on the network, is network debugging on?)" }

$screen = (& $adb shell "dumpsys display | grep -m1 mScreenState" 2>$null) -join ""
$top = (& $adb shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>$null) -join ""
Write-Host "    $($screen.Trim())  $($top.Trim() -replace '.*u0 ([^ ]+).*', '$1')"
if ($screen -match "ON" -and -not $Force) {
    Write-Host "TV screen is on, someone may be watching. Nothing done; try later or add -Force."
    exit 1
}

Write-Host "=== Installing"
& $adb install -r $apk | Write-Host
if ($LASTEXITCODE -ne 0) { throw "Install failed" }

if ($Login) {
    Write-Host "=== Logging in"
    # In standby (Doze + Low Power Standby) apps get no network, the login would fail at once.
    & $adb shell dumpsys deviceidle disable | Out-Null
    Start-Sleep 2
    & $adb logcat -c
    & powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\login.ps1" | Out-Null
    $result = $null
    for ($i = 0; $i -lt 30 -and -not $result; $i++) {
        Start-Sleep 1
        $result = & $adb logcat -d -s Kukatko:I | Select-String "login (ok|failed.*)" | Select-Object -Last 1
    }
    Write-Host "    $(if ($result) { $result.Matches[0].Value } else { 'no answer within 30 s' })"
    & $adb shell am force-stop cz.ivo.kukatko
    & $adb shell dumpsys deviceidle enable | Out-Null
}

# Google TV may hold the install for Play Protect ("unknown developer"); it shows on the TV.
$top = (& $adb shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>$null) -join ""
if ($top -match "vending|packageinstaller") { Write-Host "!!! A Play Protect / installer dialog is open on the TV: $($top.Trim())" }
$version = (& $adb shell "dumpsys package cz.ivo.kukatko | grep -m1 versionName" 2>$null) -join ""
Write-Host "=== Done: $($version.Trim())"
& $adb disconnect $Tv | Out-Null
