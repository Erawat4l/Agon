@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

REM ===========================================================================
REM  Arena AI  -  one-command APK builder for Windows
REM
REM  Usage (from this folder, in cmd):
REM     build.bat            -> builds ArenaAI-debug.apk  (installable right away)
REM     build.bat release    -> builds a release APK (signed if keystore exists)
REM     build.bat install    -> builds debug APK and installs it on a USB device
REM     build.bat clean      -> wipes build output
REM ===========================================================================

set "MODE=%~1"
if "%MODE%"=="" set "MODE=debug"

echo.
echo =============================================
echo   Arena AI  ^|  Android APK builder
echo =============================================
echo.

REM ---------- 1. Java ------------------------------------------------------
where java >nul 2>nul
if errorlevel 1 (
    if defined JAVA_HOME (
        set "PATH=%JAVA_HOME%\bin;%PATH%"
    )
)
where java >nul 2>nul
if errorlevel 1 (
    echo [X] Java not found.
    echo     Install JDK 17 ^(or 21^) and reopen this window:
    echo       winget install EclipseAdoptium.Temurin.17.JDK
    echo     then set JAVA_HOME to the folder that contains bin\java.exe
    exit /b 1
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVAVER=%%~v"
set "JAVAMAJOR=0"
for /f "delims=.-_ tokens=1" %%m in ("!JAVAVER!") do set "JAVAMAJOR=%%m"
echo [1/4] Java found: !JAVAVER!
if !JAVAMAJOR! GEQ 24 (
    REM Gradle 8.10 supports Java 17-23. Prefer a JDK 17/21 if one is installed.
    for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*" "%ProgramFiles%\Eclipse Adoptium\jdk-21*" "%ProgramFiles%\Java\jdk-17*" "%ProgramFiles%\Microsoft\jdk-17*" "%USERPROFILE%\.jdks\*17*" "%USERPROFILE%\.jdks\*21*" "%ProgramFiles%\Android\Android Studio\jbr") do (
        if exist "%%~fd\bin\java.exe" if not defined JDKOK set "JDKOK=%%~fd"
    )
    if defined JDKOK (
        set "JAVA_HOME=!JDKOK!"
        set "PATH=!JDKOK!\bin;!PATH!"
        echo        Java !JAVAMAJOR! is too new for the build tools - switched to: !JDKOK!
    ) else (
        echo        [X] Java !JAVAMAJOR! is too new for Gradle 8.10.
        echo            Install JDK 17: winget install EclipseAdoptium.Temurin.17.JDK
        exit /b 1
    )
)

REM ---------- 2. Android SDK ----------------------------------------------
set "SDK="
if defined ANDROID_HOME if exist "%ANDROID_HOME%" set "SDK=%ANDROID_HOME%"
if not defined SDK if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%" set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK if exist "%USERPROFILE%\AppData\Local\Android\Sdk" set "SDK=%USERPROFILE%\AppData\Local\Android\Sdk"
if not defined SDK if exist "%ProgramFiles%\Android\Android Studio\Sdk" set "SDK=%ProgramFiles%\Android\Android Studio\Sdk"

if not defined SDK (
    echo [X] Android SDK not found.
    echo     Easiest fix: install Android Studio once ^(it ships the SDK^):
    echo       winget install Google.AndroidStudio
    echo     then re-run this script. Or set ANDROID_HOME to your SDK folder.
    exit /b 1
)
echo [2/4] Android SDK: !SDK!

if not exist "!SDK!\platform-tools" echo        ^(note: platform-tools missing, adb install won't work^)

REM Gradle reads local.properties for the SDK path (this file is git-ignored).
> local.properties echo sdk.dir=!SDK:\=\\!

REM ---------- 3. Build ----------------------------------------------------
if /i "%MODE%"=="clean" (
    echo [3/4] Cleaning...
    call gradlew.bat clean
    if errorlevel 1 goto :failed
    echo Done.
    exit /b 0
)

echo [3/4] Compiling... first run downloads Gradle + build tools ^(a few minutes^).
if /i "%MODE%"=="release" (
    call gradlew.bat --no-daemon assembleRelease
) else (
    call gradlew.bat --no-daemon assembleDebug
)
if errorlevel 1 goto :failed

REM ---------- 4. Collect the APK -----------------------------------------
set "APK="
if /i "%MODE%"=="release" (
    for %%f in ("app\build\outputs\apk\release\*.apk") do set "APK=%%~ff"
) else (
    for %%f in ("app\build\outputs\apk\debug\*.apk") do set "APK=%%~ff"
)
if not defined APK (
    echo [X] Build finished but no APK was produced.
    exit /b 1
)

if /i "%MODE%"=="release" (
    copy /y "!APK!" "ArenaAI-release.apk" >nul
    set "FINAL=%~dp0ArenaAI-release.apk"
) else (
    copy /y "!APK!" "ArenaAI-debug.apk" >nul
    set "FINAL=%~dp0ArenaAI-debug.apk"
)

echo.
echo =============================================
echo   [OK] APK ready:
echo        !FINAL!
echo =============================================
echo.

if /i "%MODE%"=="install" (
    echo Installing on connected device...
    "!SDK!\platform-tools\adb.exe" install -r "!FINAL!"
    if errorlevel 1 (
        echo.
        echo [X] Install failed. Enable USB debugging, connect the phone,
        echo     and accept the "Allow USB debugging" prompt, then retry.
        exit /b 1
    )
    echo Launching Arena...
    "!SDK!\platform-tools\adb.exe" shell monkey -p ai.arena.webapp.debug -c android.intent.category.LAUNCHER 1 >nul 2>nul
    echo Installed.
    exit /b 0
)

echo Next steps:
echo   * Copy the APK to your phone and tap it ^(allow "install unknown apps"^).
echo   * Or with a USB cable:  build.bat install
echo.
exit /b 0

:failed
echo.
echo [X] Build failed. Scroll up for the first error message.
echo     Common causes:
echo       - Wrong Java version: this project needs JDK 17 or newer.
echo       - No internet on first run: Gradle must download dependencies.
echo       - Antivirus blocking the Gradle daemon.
exit /b 1
