@echo off
setlocal EnableExtensions EnableDelayedExpansion
REM ===========================================================================
REM  Agon - clean this PC after the first Windows build
REM
REM  The first build left a lot behind that is no longer needed:
REM
REM     desktop\node_modules     ~500 MB of packages, re-downloaded on demand
REM     desktop\release          the packaging output (the .exe files are copied up here)
REM     .gradle, app\build       Gradle cache and the compiled APK
REM     %LOCALAPPDATA%\electron-builder\Cache
REM                              the signing-tools download that failed to unpack
REM
REM  Usage:
REM     clean-all.bat            delete all of the above  (asks once, then does it)
REM     clean-all.bat /yes       no question
REM
REM  What is NOT touched: the sources, git history, your APK and EXE files in this
REM  folder, and anything outside this project except the electron-builder cache.
REM ===========================================================================

cd /d "%~dp0"

set "ASK=%~1"
echo.
echo =============================================
echo   Agon  ^|  clean the local build output
echo =============================================
echo.
echo This folder: %~dp0
echo.

if /i not "%ASK%"=="/yes" (
    echo Delete: desktop\node_modules, desktop\release, .gradle, app\build
    echo         %LOCALAPPDATA%\electron-builder\Cache
    echo Keep  : sources, git, Agon.apk, Agon.exe, Agon-*.exe
    echo.
    set "ANSWER="
    set /p "ANSWER=Continue? [y/N] "
    if /i not "!ANSWER!"=="y" goto :cancelled
)

echo.
echo [1/5] desktop\node_modules...
if exist "desktop\node_modules" (
    rmdir /s /q "desktop\node_modules"
    if exist "desktop\node_modules" echo        [X] could not delete - close any editor/terminal using it.
    if not exist "desktop\node_modules" echo        removed
) else (
    echo        already gone
)

echo [2/5] desktop\release...
if exist "desktop\release" (
    rmdir /s /q "desktop\release"
    echo        removed
) else (
    echo        already gone
)

echo [3/5] app\build + .gradle...
if exist "app\build" rmdir /s /q "app\build"
if exist ".gradle" rmdir /s /q ".gradle"
echo        removed

echo [4/5] electron-builder cache...
if exist "%LOCALAPPDATA%\electron-builder\Cache" (
    rmdir /s /q "%LOCALAPPDATA%\electron-builder\Cache"
    if exist "%LOCALAPPDATA%\electron-builder\Cache" echo        [X] partially left - it is only a cache, ignore it.
    if not exist "%LOCALAPPDATA%\electron-builder\Cache" echo        removed
) else (
    echo        already gone
)

echo [5/5] leftovers in this folder...
if exist "desktop\app.asar" del /q "desktop\app.asar" >nul 2>nul

echo.
echo =============================================
echo   [DONE] kept in this folder
echo =============================================
if exist "Agon.apk" echo   Agon.apk
if exist "Agon.exe" echo   Agon.exe
if exist "Agon-32.exe" echo   Agon-32.exe
if exist "Agon-debug.apk" echo   Agon-debug.apk
if exist "Agon-release.apk" echo   Agon-release.apk
for %%f in ("Agon-Setup-*.exe" "Agon-Portable-*.exe") do (
    if exist "%%~f" echo   %%~nxf
)
echo =============================================
echo.
echo To rebuild everything later:  build-all.bat
echo   (the first run downloads the packages again, a few minutes)
echo.
exit /b 0

:cancelled
echo.
echo Cancelled - nothing was deleted.
exit /b 0
