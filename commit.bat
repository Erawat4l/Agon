@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

REM ===========================================================================
REM  Arena AI - ALL-IN-ONE update command
REM
REM  One command does everything:
REM      1. pull the latest code from GitHub
REM      2. bump the version (from the latest release tag)
REM      3. build the APK
REM      4. commit + push everything
REM      5. publish a GitHub Release with the APK attached
REM      6. install it on a USB-connected phone (if adb sees one)
REM      7. open the release page so you can download it anywhere
REM
REM  Usage:
REM     commit.bat                          auto commit message, patch bump
REM     commit.bat "my changes"             custom commit message
REM     commit.bat "my changes" 1.3.0       pick the version
REM     commit.bat "my changes" skipbuild   commit + push only
REM     commit.bat "my changes" beta        publish as a pre-release
REM
REM  First time only, store the GitHub token:
REM     publish.bat --token github_pat_xxx
REM ===========================================================================

set "MSG=%~1"
set "ARG2=%~2"
set "ARG3=%~3"

if "%MSG%"=="" set "MSG=update %DATE% %TIME:~0,5%"

set "CHANNEL=stable"
set "FIXEDVER="
set "SKIPBUILD="

if /i "%ARG2%"=="skipbuild" set "SKIPBUILD=1"
if /i "%ARG2%"=="beta" set "CHANNEL=beta"
if /i "%ARG2%"=="release" set "CHANNEL=stable"
if /i "%ARG3%"=="skipbuild" set "SKIPBUILD=1"
if /i "%ARG3%"=="beta" set "CHANNEL=beta"
call :maybe_version "%ARG2%"
if not defined FIXEDVER call :maybe_version "%ARG3%"

echo.
echo =============================================
echo   Arena AI  ^|  all-in-one update
echo =============================================
echo.

REM ---------- 0. sanity ---------------------------------------------------
git rev-parse --is-inside-work-tree >nul 2>nul
if errorlevel 1 goto :no_git
for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD') do set "BRANCH=%%b"
echo [1/7] Branch: !BRANCH!

REM ---------- 1. pull -----------------------------------------------------
git pull --rebase --autostash origin "!BRANCH!" >nul 2>nul
if errorlevel 1 (
    echo        pull skipped ^(no upstream yet or conflicts - continuing^)
) else (
    echo        up to date with origin/!BRANCH!
)

REM ---------- 2. version --------------------------------------------------
if defined FIXEDVER goto :have_version
call :bump_version
set "LASTTAG="
for /f "delims=" %%t in ('git tag --list "v*" --sort=-v:refname') do if not defined LASTTAG set "LASTTAG=%%t"
if defined LASTTAG goto :got_tag
REM No tags yet: version.txt is the source of truth (written on every publish).
if not exist version.txt set "LASTTAG=1.0.0"
if exist version.txt set /p LASTTAG=<version.txt
:got_tag
set "LASTTAG=!LASTTAG:v=!"
set "MAJ=1"
set "MIN=0"
set "PAT=0"
for /f "tokens=1,2,3 delims=. " %%a in ("!LASTTAG!") do (
    set "MAJ=%%a"
    set "MIN=%%b"
    if not "%%c"=="" set "PAT=%%c"
)
set /a "PAT+=1"
set "VERSION=!MAJ!.!MIN!.!PAT!"
REM Remember it, so the next run (even without tags) bumps from here.
> version.txt echo !VERSION!
goto :eof

:no_git
for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD') do set "BRANCH=%%b"
echo [1/7] Branch: !BRANCH!

REM ---------- 1. pull -----------------------------------------------------
git pull --rebase --autostash origin "!BRANCH!" >nul 2>nul
if errorlevel 1 (
    echo        pull skipped ^(no upstream yet or conflicts - continuing^)
) else (
    echo        up to date with origin/!BRANCH!
)

REM ---------- 2. version --------------------------------------------------
if defined FIXEDVER goto :have_version
call :bump_version
:have_version
for /f "tokens=1,2,3 delims=." %%a in ("!VERSION!") do (
    set /a "VCODE=%%a*1000000 + %%b*1000 + %%c"
)
set "TAG=v!VERSION!"
echo [2/7] Version: !VERSION! ^(code !VCODE!, !CHANNEL!^)

if defined SKIPBUILD goto :push

REM ---------- 3. build ----------------------------------------------------
echo [3/7] Building APK...
call gradlew.bat --no-daemon assembleDebug "-PVERSION_NAME=!VERSION!" "-PVERSION_CODE=!VCODE!"
if errorlevel 1 goto :build_failed
set "APKSRC=app\build\outputs\apk\debug\app-debug.apk"
set "APKOUT=ArenaAI-!VERSION!.apk"
if not exist "!APKSRC!" goto :build_failed
copy /y "!APKSRC!" "!APKOUT!" >nul
echo        !APKOUT!

REM ---------- 4. commit + push -------------------------------------------
:push
echo [4/7] Committing and pushing...
git add -A
git diff --cached --quiet
if not errorlevel 1 goto :nothing_new
git config user.email >nul 2>nul
if errorlevel 1 git config user.email "arena@localhost"
git config user.name >nul 2>nul
if errorlevel 1 git config user.name "Arena"
git commit -m "%MSG%" >nul
echo        committed: %MSG%
goto :do_push

:nothing_new
echo        nothing new to commit

:do_push
git push origin "!BRANCH!"
if errorlevel 1 (
    echo.
    echo [X] Push failed - the branch moved on GitHub. Try again, or run:
    echo       git pull --rebase ^&^& commit.bat
    exit /b 1
)

if defined SKIPBUILD goto :done

REM ---------- 5. publish the release -------------------------------------
echo [5/7] Publishing release !TAG! with the APK...
call publish.bat "!VERSION!" !CHANNEL!
if errorlevel 1 goto :publish_failed

REM ---------- 6. install on the connected phone ---------------------------
echo [6/7] Checking for a USB-connected phone...
set "ADB="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB goto :no_adb
"!ADB!" devices 2>nul | findstr /r /c:"device$" >nul
if errorlevel 1 goto :no_device
"!ADB!" install -r "!APKOUT!"
if errorlevel 1 (
    echo        install failed - uninstall the old copy once, then re-run
) else (
    echo        installed on the phone
)
goto :open_release

:no_device
echo        no phone on USB - skipping ^(the release is still published^)
goto :open_release

:no_adb
echo        adb not installed - skipping

REM ---------- 7. open the release page ------------------------------------
:open_release
echo [7/7] Opening the release page...
start "" "https://github.com/Erawat4l/Arena-Ai-App/releases/tag/!TAG!"

:done
echo.
echo =============================================
echo   Done - Arena !VERSION!
echo =============================================
echo   APK here:      %~dp0ArenaAI-!VERSION!.apk
echo   Release page:  https://github.com/Erawat4l/Arena-Ai-App/releases/tag/!TAG!
echo   Installed apps: Update -^> Check for updates
echo.
exit /b 0

REM ---------- helpers ----------------------------------------------------
:maybe_version
REM Treat purely numeric "x.y.z" arguments as an explicit version.
if "%~1"=="" goto :eof
echo %~1| findstr /r "^[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*$" >nul
if errorlevel 1 goto :eof
set "FIXEDVER=%~1"
goto :eof

:bump_version
set "LASTTAG="
for /f "delims=" %%t in ('git tag --list "v*" --sort=-v:refname') do if not defined LASTTAG set "LASTTAG=%%t"
if defined LASTTAG goto :got_tag
REM No tags yet: fall back to the version declared in app/build.gradle.
set "LASTTAG="
for /f "tokens=3" %%v in ('findstr /c:"VERSION_NAME" app\build.gradle') do set "LASTTAG=%%v"
set "LASTTAG=!LASTTAG:*'=!"
set "LASTTAG=!LASTTAG:'=!"
set "LASTTAG=!LASTTAG:.toString())=!"
if not defined LASTTAG set "LASTTAG=1.2.0"
:got_tag
set "LASTTAG=!LASTTAG:v=!"
set "MAJ=1"
set "MIN=0"
set "PAT=0"
for /f "tokens=1,2,3 delims=." %%a in ("!LASTTAG!") do (
    set "MAJ=%%a"
    set "MIN=%%b"
    if not "%%c"=="" set "PAT=%%c"
)
set /a "PAT+=1"
set "VERSION=!MAJ!.!MIN!.!PAT!"
goto :eof

:no_git
echo [X] This folder is not a git repository.
echo     Clone it first:  git clone -b arena/0b045796-arena-ai-app https://github.com/Erawat4l/Arena-Ai-App.git
exit /b 1

:build_failed
echo.
echo [X] Build failed - scroll up for the first error.
exit /b 1

:publish_failed
echo.
echo [X] The build and push worked, but publishing failed.
echo     Most likely the GitHub token: publish.bat --token github_pat_xxx
echo     You can also upload ArenaAI-!VERSION!.apk by hand on the Releases page.
exit /b 1
