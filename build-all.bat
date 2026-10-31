@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

REM ===========================================================================
REM  Agon - build BOTH the Android APK and the Windows EXE with one command
REM
REM  Usage:
REM     build-all.bat            APK (debug) + Windows installer + portable EXE
REM     build-all.bat release    APK (release, signed if keystore.properties exists)
REM                              + Windows installer + portable EXE
REM
REM  What you get, in this folder:
REM     Agon-debug.apk                     (or Agon-release.apk)  <- phones
REM     Agon-Setup-<ver>-x64.exe / -ia32.exe                      <- installer (the EXE)
REM     Agon-Portable-<ver>-x64.exe / -ia32.exe                   <- no install needed
REM
REM  The APK needs: JDK 17-21 + Android SDK (build.bat finds them).
REM  The EXE needs: Node.js 18+  (winget install OpenJS.NodeJS.LTS)
REM  Missing one is not fatal: the other half is still built and the summary says so.
REM
REM  Note: no for-loops inside parenthesised blocks - cmd.exe mis-parses those.
REM ===========================================================================

set "MODE=%~1"
if "%MODE%"=="" set "MODE=debug"

echo.
echo =============================================
echo   Agon  ^|  APK + Windows EXE builder
echo =============================================
echo.

set "APK_OK=0"
set "EXE_OK=0"
set "NPM="

REM ---------- 1. Android APK -------------------------------------------------
echo [1/2] Android APK...
call "%~dp0build.bat" %MODE%
if errorlevel 1 goto :apk_failed
set "APK_OK=1"
echo.
goto :desktop

:apk_failed
echo.
echo [1/2] Android APK: FAILED - the Windows build will still be tried.
echo.

REM ---------- 2. Windows EXE -------------------------------------------------
:desktop
echo [2/2] Windows EXE...
where npm >nul 2>nul
if errorlevel 1 goto :no_node
set "NPM=npm"

if not exist "desktop\package.json" goto :no_desktop
if exist "desktop\node_modules\electron\package.json" goto :have_modules
echo        Installing the desktop dependencies ^(first run only, a few minutes^)...
echo        ^(deprecation warnings and "install scripts" notes are normal - ignore them^)
pushd desktop
call npm install --no-audit --no-fund
set "INSTALL_RC=!errorlevel!"
popd
if "!INSTALL_RC!"=="0" goto :have_modules
REM npm can exit non-zero on its own postinstall-script notice while node_modules is
REM complete. Only a missing electron binary means the install really failed.
if exist "desktop\node_modules\electron\package.json" goto :have_modules
echo        [X] npm install failed - see the messages above.
goto :exe_failed

:have_modules
echo        Packaging the Windows installers ^(x64 + ia32^)...
pushd desktop
call npm run dist
set "DIST_RC=!errorlevel!"
popd
if not "!DIST_RC!"=="0" goto :desktop_build_failed
if not exist "desktop\release" goto :nothing_built

REM Copy every produced .exe up here, keeping the name electron-builder gave it:
REM   Agon-Setup-<version>-x64.exe / -ia32.exe       the installer (what to send people)
REM   Agon-Portable-<version>-x64.exe / -ia32.exe    runs without installing
for %%f in ("desktop\release\*.exe") do (
    if exist "%%~f" copy /y "%%~f" "%~dp0%%~nxf" >nul
)
set "EXE_OK=1"
goto :summary

:desktop_build_failed
echo        [X] electron-builder failed - see the messages above.
echo            "Cannot create symbolic link" means Windows refused to unpack the signing
echo            tools - that should not happen now. If it does: delete
echo            %LOCALAPPDATA%\electron-builder\Cache and run this again.
echo            For an exe that carries the Agon icon, turn on Windows Developer Mode
echo            and run:  cd desktop ^&^& npm run dist:branded
goto :exe_failed

:nothing_built
echo        [X] The build finished but desktop\release is empty.
goto :exe_failed

:exe_failed
echo.
echo [2/2] Windows EXE: FAILED.
goto :summary

:no_node
echo        [X] Node.js not found. Install it, then run this again:
echo            winget install OpenJS.NodeJS.LTS
goto :summary

:no_desktop
echo        [X] desktop\package.json is missing - is this the right folder?
goto :summary

REM ---------- summary --------------------------------------------------------
:summary
echo.
echo =============================================
echo   [DONE] what was built
echo =============================================
if "!APK_OK!"=="1" (
    if /i "%MODE%"=="release" (
        echo   APK   : %~dp0Agon-release.apk
    ) else (
        echo   APK   : %~dp0Agon-debug.apk
    )
) else (
    echo   APK   : not built
)
if "!EXE_OK!"=="1" (
    echo   EXE   : %~dp0Agon-Setup-*.exe       ^(installer, x64 + ia32^)
    echo           %~dp0Agon-Portable-*.exe    ^(portable, x64 + ia32^)
) else (
    echo   EXE   : not built
)
echo =============================================
echo.

if "!APK_OK!"=="1" if "!EXE_OK!"=="1" exit /b 0
exit /b 1
