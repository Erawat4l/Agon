@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

REM ===========================================================================
REM  Agon - one-command APK builder for Windows (app for arena.ai)
REM
REM  Usage (from this folder, in cmd):
REM     build.bat            -> builds Agon-debug.apk  (installable right away)
REM     build.bat release    -> builds a release APK (signed if keystore exists)
REM     build.bat install    -> builds debug APK and installs it on a USB device
REM     build.bat clean      -> wipes build output
REM
REM  Note: this script deliberately avoids for-loops inside parenthesised
REM  blocks, which cmd.exe mis-parses ("do was unexpected at this time").
REM ===========================================================================

set "MODE=%~1"
if "%MODE%"=="" set "MODE=debug"

echo.
echo =============================================
echo   Agon  ^|  Android APK builder
echo =============================================
echo.

REM ---------- 1. Java ------------------------------------------------------
where java >nul 2>nul
if not errorlevel 1 goto :java_ok
if not defined JAVA_HOME goto :no_java
if not exist "%JAVA_HOME%\bin\java.exe" goto :no_java
set "PATH=%JAVA_HOME%\bin;%PATH%"

:java_ok
set "JAVAVER=unknown"
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVAVER=%%~v"
set "JAVAMAJOR=0"
for /f "delims=.-_ tokens=1" %%m in ("!JAVAVER!") do set "JAVAMAJOR=%%m"
echo [1/4] Java found: !JAVAVER!
if !JAVAMAJOR! LSS 24 goto :sdk

REM Newer Gradle versions reject Java 24+; switch to a JDK 17/21 if present.
set "JDKOK="
for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do call :take_jdk "%%~fd"
for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do call :take_jdk "%%~fd"
for /d %%d in ("%ProgramFiles%\Java\jdk-17*") do call :take_jdk "%%~fd"
for /d %%d in ("%ProgramFiles%\Microsoft\jdk-17*") do call :take_jdk "%%~fd"
for /d %%d in ("%USERPROFILE%\.jdks\*17*") do call :take_jdk "%%~fd"
for /d %%d in ("%USERPROFILE%\.jdks\*21*") do call :take_jdk "%%~fd"
for /d %%d in ("%ProgramFiles%\Android\Android Studio\jbr") do call :take_jdk "%%~fd"
if not defined JDKOK goto :java_too_new
set "JAVA_HOME=!JDKOK!"
set "PATH=!JDKOK!\bin;!PATH!"
echo        Java !JAVAMAJOR! is too new for the build tools - using !JDKOK!
goto :sdk

:take_jdk
if defined JDKOK goto :eof
if not exist "%~1\bin\java.exe" goto :eof
set "JDKOK=%~1"
goto :eof

:no_java
echo [X] Java not found.
echo     Install JDK 17 ^(or 21^) and reopen this window:
echo       winget install EclipseAdoptium.Temurin.17.JDK
echo     then set JAVA_HOME to the folder that contains bin\java.exe
exit /b 1

:java_too_new
echo [X] Java !JAVAMAJOR! is too new for Gradle 8.10 ^(needs Java 17-23^).
echo     Install JDK 17: winget install EclipseAdoptium.Temurin.17.JDK
echo     then set JAVA_HOME to that JDK and re-run.
exit /b 1

REM ---------- 2. Android SDK ----------------------------------------------
:sdk
set "SDK="
call :try_sdk "%ANDROID_HOME%"
call :try_sdk "%ANDROID_SDK_ROOT%"
call :try_sdk "%LOCALAPPDATA%\Android\Sdk"
call :try_sdk "%USERPROFILE%\AppData\Local\Android\Sdk"
call :try_sdk "%ProgramFiles%\Android\Android Studio\Sdk"
if defined SDK goto :sdk_ok

echo [X] Android SDK not found.
echo.
echo     Option A - no Android Studio needed, ~5 min, all inside cmd:
echo       mkdir "%LOCALAPPDATA%\Android\Sdk\cmdline-tools" 2^>nul
echo       curl -L -o "%TEMP%\cmdtools.zip" https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
echo       tar -xf "%TEMP%\cmdtools.zip" -C "%LOCALAPPDATA%\Android\Sdk\cmdline-tools"
echo       ren "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\cmdline-tools" latest
echo       (for /l %%i in (1,1,30) do @echo y) ^| "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
echo       "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo     then run build.bat again - the default SDK path is auto-detected.
echo.
echo     Option B - Android Studio ^(includes the SDK and a GUI^):
echo       winget install Google.AndroidStudio
echo.
echo     Option C - SDK already somewhere else: set ANDROID_HOME to that folder.
exit /b 1

:try_sdk
if defined SDK goto :eof
if "%~1"=="" goto :eof
if not exist "%~1" goto :eof
set "SDK=%~1"
goto :eof

:sdk_ok
echo [2/4] Android SDK: !SDK!
if exist "!SDK!\platform-tools\adb.exe" goto :sdk_note
echo        ^(note: platform-tools missing, "build.bat install" won't work^)
:sdk_note

REM Gradle reads local.properties for the SDK path (this file is git-ignored).
set "SDKWIN=!SDK:\=\\!"
> local.properties echo sdk.dir=!SDKWIN!

REM ---------- 3. Build ----------------------------------------------------
if /i "%MODE%"=="clean" goto :do_clean
if /i "%MODE%"=="release" goto :do_release

REM Run the resource pre-flight so broken strings fail with a readable message.
where python >nul 2>nul
if errorlevel 1 goto :no_preflight
python tools\check-resources.py
if errorlevel 1 goto :bad_resources
:no_preflight

echo [3/4] Compiling debug APK... first run downloads Gradle + build tools ^(a few minutes^).
call gradlew.bat --no-daemon assembleDebug
if errorlevel 1 goto :failed
set "OUTDIR=app\build\outputs\apk\debug"
set "FINALNAME=Agon-debug.apk"
goto :collect

:do_release
if exist "keystore.properties" goto :release_build
echo        [i] No keystore found - the release APK will be UNSIGNED.
echo            For an installable APK run make-keystore.bat once.
:release_build
echo [3/4] Compiling release APK...
call gradlew.bat --no-daemon assembleRelease
if errorlevel 1 goto :failed
set "OUTDIR=app\build\outputs\apk\release"
set "FINALNAME=Agon-release.apk"
goto :collect

:do_clean
echo [3/4] Cleaning...
call gradlew.bat clean
if errorlevel 1 goto :failed
echo Done.
exit /b 0

REM ---------- 4. Collect the APK -----------------------------------------
:collect
set "APK="
for %%f in ("!OUTDIR!\*.apk") do set "APK=%%~ff"
if defined APK goto :copy_apk
echo [X] Build finished but no APK was produced.
exit /b 1

:copy_apk
copy /y "!APK!" "!FINALNAME!" >nul
set "FINAL=%~dp0!FINALNAME!"

echo.
echo =============================================
echo   [OK] APK ready:
echo        !FINAL!
echo =============================================
echo.

if /i not "%MODE%"=="install" goto :done
if not exist "!SDK!\platform-tools\adb.exe" goto :no_adb

echo Installing on the connected device...
"!SDK!\platform-tools\adb.exe" install -r "!FINAL!"
if errorlevel 1 goto :install_failed
echo Launching Arena...
"!SDK!\platform-tools\adb.exe" shell monkey -p ai.arena.webapp.debug -c android.intent.category.LAUNCHER 1 >nul 2>nul
echo Installed.
exit /b 0

:no_adb
echo [X] adb not found at "!SDK!\platform-tools\adb.exe".
echo     Install it: "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools"
exit /b 1

:install_failed
echo.
echo [X] Install failed. Enable USB debugging, connect the phone, and accept
echo     the "Allow USB debugging" prompt, then retry.
echo     Tip: the debug APK installs as a separate app (Arena debug).
exit /b 1

:done
echo Next steps:
echo   * Copy the APK to your phone and tap it ^(allow "install unknown apps"^).
echo   * Or with a USB cable:  build.bat install
echo.
exit /b 0

:bad_resources
echo.
echo [X] Fix the resource problems listed above, then re-run build.bat.
exit /b 1

:failed
echo.
echo [X] Build failed. Scroll up for the first error message.
echo     Common causes:
echo       - Wrong Java version: this project needs JDK 17-23.
echo       - No internet on first run: Gradle must download dependencies.
echo       - Antivirus blocking the Gradle daemon.
exit /b 1
