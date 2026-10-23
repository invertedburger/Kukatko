@echo off
rem Build Kukatko, start the Android TV emulator if no device is connected, install, launch, show the app's log.
rem
rem   run.bat            debug build (quick to build) + log
rem   run.bat release    optimised build, as it should go on the TV (R8 makes the build ~1 min slower)
rem   run.bat login      also log in with %USERPROFILE%\.kukatko.env (first run / after data clear)
rem   run.bat nolog      don't follow the log at the end
rem   run.bat tv 192.168.0.50   go to the real TV over adb (adb connect) instead of the emulator;
rem                              plain "tv" uses %KUKATKO_TV% (set it once with: setx KUKATKO_TV 192.168.0.50)
rem Options combine, e.g. "run.bat release login tv 192.168.0.50".
setlocal
cd /d "%~dp0"

set "DEV=C:\Users\you\android-dev"
set "JAVA_HOME=%DEV%\jdk17"
set "ANDROID_SDK_ROOT=%DEV%\sdk"
set "ADB=%DEV%\sdk\platform-tools\adb.exe"
set "AVD=KukatkoTV"

set VARIANT=debug
set LOGIN=
set FOLLOW=1
set TV=
:args
if "%~1"=="" goto args_done
if /i "%~1"=="release" set VARIANT=release
if /i "%~1"=="login" set LOGIN=1
if /i "%~1"=="nolog" set FOLLOW=
if /i "%~1"=="tv" (
    set "TV=%KUKATKO_TV%"
    echo %~2| findstr /r "^[0-9][0-9.:]*[0-9]$" >nul && (set "TV=%~2" & shift)
    if not defined TV (
        echo "tv" needs an address: run.bat tv 192.168.0.50 ^(or setx KUKATKO_TV 192.168.0.50^)
        exit /b 1
    )
)
shift
goto args
:args_done

rem Aim adb at the TV; ANDROID_SERIAL is inherited by tools\login.ps1 too, so nothing else needs to know.
if not defined TV goto tv_done
echo %TV%| findstr ":" >nul || set "TV=%TV%:5555"
echo === Connecting to the TV %TV%
"%ADB%" connect %TV%
set "ANDROID_SERIAL=%TV%"
:tv_done

if "%VARIANT%"=="debug" (set TASK=assembleDebug) else (set TASK=assembleRelease)
set "APK=app\build\outputs\apk\%VARIANT%\app-%VARIANT%.apk"

echo === Building (%VARIANT%)
call "%~dp0gradlew.bat" %TASK% --console=plain -q
if errorlevel 1 (
    echo Build failed.
    exit /b 1
)

"%ADB%" get-state >nul 2>&1
if not errorlevel 1 goto device_ready
if defined TV (
    echo TV %TV% did not connect. On the TV: Developer options - USB/Network debugging on, then accept the prompt.
    exit /b 1
)
echo === No device connected, starting emulator %AVD%
start "Kukatko emulator" /min "%DEV%\sdk\emulator\emulator.exe" -avd %AVD% -no-snapshot-save -gpu auto -no-boot-anim
"%ADB%" wait-for-device
:wait_boot
set BOOT=
for /f "delims=" %%b in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%b
if "%BOOT%"=="1" goto device_ready
ping -n 3 127.0.0.1 >nul
goto wait_boot

:device_ready
echo === Installing
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo Install failed.
    exit /b 1
)

echo === Starting
"%ADB%" shell am force-stop cz.ivo.kukatko
"%ADB%" logcat -c
"%ADB%" shell am start -n cz.ivo.kukatko/.MainActivity >nul
if defined LOGIN powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\login.ps1"

if not defined FOLLOW exit /b 0
ping -n 3 127.0.0.1 >nul
set PID=
for /f "delims=" %%p in ('"%ADB%" shell pidof cz.ivo.kukatko') do set PID=%%p
if not defined PID (
    echo App is not running - showing crashes:
    "%ADB%" logcat -d -b crash
    exit /b 1
)
echo === App log (Ctrl+C to stop)
"%ADB%" logcat --pid=%PID% -v time *:I
