@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

REM ===========================================================================
REM  Agon - ALL-IN-ONE update command
REM
REM  One command does everything:
REM    1. pull the latest code from GitHub
REM    2. bump the version (from version.txt, else the newest v* tag)
REM    3. build the APK
REM    4. commit + push everything
REM    5. publish a GitHub Release with the APK attached
REM    6. install it on a USB-connected phone (if adb sees one)
REM    7. open the release page
REM
REM  Usage:
REM    commit.bat                          auto commit message, patch bump
REM    commit.bat "my changes"             custom commit message
REM    commit.bat "my changes" 1.3.0       pick the version
REM    commit.bat "my changes" skipbuild   commit + push only
REM    commit.bat "my changes" beta        publish as a pre-release
REM
REM  First time only:  publish.bat --token github_pat_xxx
REM ===========================================================================

set "MSG=%~1"
set "ARG2=%~2"
set "ARG3=%~3"

if "%MSG%"=="" set "MSG=update %DATE% %TIME:~0,5%"

set "CHANNEL=stable"
set "FIXEDVER="
set "SKIPBUILD="

call :classify "%ARG2%"
call :classify "%ARG3%"

echo.
echo =============================================
echo   Agon  ^|  all-in-one update
echo =============================================
echo.

REM ---------- 0. sanity --------------------------------------------------
git rev-parse --is-inside-work-tree >nul 2>nul
if errorlevel 1 goto :no_git
for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD') do set "BRANCH=%%b"
echo [1/7] Branch: !BRANCH!

REM ---------- 1. pull ----------------------------------------------------
REM version.txt used to be committed, so a local bump made every later pull die with
REM "Your local changes would be overwritten by merge". It is generated now: clear any
REM stale local copy, pull, and only then fail with instructions.
git rm --cached version.txt >nul 2>nul
git checkout -- version.txt >nul 2>nul
git pull --rebase --autostash origin "!BRANCH!" >nul 2>nul
if not errorlevel 1 goto :pulled
echo        pull retry ^(no rebase^)...
git rebase --abort >nul 2>nul
git checkout -- version.txt >nul 2>nul
git pull --no-rebase --autostash origin "!BRANCH!"
if errorlevel 1 goto :pull_failed
:pulled
echo        up to date with origin/!BRANCH!

REM ---------- 2. version -------------------------------------------------
if defined FIXEDVER goto :have_version
call :bump_version
:have_version
for /f "tokens=1,2,3 delims=." %%a in ("!VERSION!") do set /a "VCODE=%%a*1000000 + %%b*1000 + %%c"
set "TAG=v!VERSION!"
echo [2/7] Version: !VERSION! ^(code !VCODE!, !CHANNEL!^)

if defined SKIPBUILD goto :push

REM ---------- 3. build ---------------------------------------------------
echo [3/7] Building APK...
call gradlew.bat --no-daemon assembleDebug "-PVERSION_NAME=!VERSION!" "-PVERSION_CODE=!VCODE!"
if errorlevel 1 goto :build_failed
set "APKSRC=app\build\outputs\apk\debug\app-debug.apk"
set "APKOUT=Agon-!VERSION!.apk"
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
if errorlevel 1 goto :push_failed

if defined SKIPBUILD goto :done

REM ---------- 5. publish -------------------------------------------------
echo [5/7] Publishing release !TAG! with the APK...
call publish.bat "!VERSION!" !CHANNEL!
if errorlevel 1 goto :publish_failed

REM ---------- 6. phone ---------------------------------------------------
echo [6/7] Looking for a USB-connected phone...
set "ADB="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB goto :no_adb
"!ADB!" devices 2>nul | findstr /r /c:"device$" >nul
if errorlevel 1 goto :no_device
"!ADB!" install -r "!APKOUT!"
if errorlevel 1 goto :install_failed
echo        installed on the phone
goto :open_release

:no_adb
echo        adb not installed - skipping
goto :open_release

:no_device
echo        no phone on USB - skipping
goto :open_release

:install_failed
echo        install failed - uninstall the old copy once, then re-run
goto :open_release

REM ---------- 7. release page -------------------------------------------
:open_release
echo [7/7] Opening the release page...
start "" "https://github.com/Erawat4l/Agon/releases/tag/!TAG!"

:done
echo.
echo =============================================
echo   Done - Arena !VERSION!
echo =============================================
echo   APK:          %~dp0Agon-!VERSION!.apk
echo   Release:      https://github.com/Erawat4l/Agon/releases/tag/!TAG!
echo   In the app:   More -^> Update -^> Check for updates
echo.
exit /b 0

REM ---------- helpers ---------------------------------------------------
:classify
REM Pure "x.y.z" means an explicit version; the words are flags.
if "%~1"=="" goto :eof
if /i "%~1"=="skipbuild" set "SKIPBUILD=1"
if /i "%~1"=="beta" set "CHANNEL=beta"
echo %~1| findstr /r "^[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*$" >nul
if not errorlevel 1 set "FIXEDVER=%~1"
goto :eof

:bump_version
REM version.txt is the local baseline (git-ignored); the newest release tag is only a
REM fallback for a fresh clone that has never published anything.
set "BASE="
if exist version.txt set /p BASE=<version.txt
if defined BASE goto :bump_now
for /f "delims=" %%t in ('git tag --list "v*" --sort=-v:refname') do if not defined BASE set "BASE=%%t"
if defined BASE goto :bump_now
set "BASE=1.0.0"
:bump_now
set "BASE=!BASE:v=!"
set "MAJ=1"
set "MIN=0"
set "PAT=0"
for /f "tokens=1,2,3 delims=. " %%a in ("!BASE!") do (
    set "MAJ=%%a"
    set "MIN=%%b"
    if not "%%c"=="" set "PAT=%%c"
)
set /a "PAT+=1"
set "VERSION=!MAJ!.!MIN!.!PAT!"
> version.txt echo !VERSION!
goto :eof

:pull_failed
echo.
echo [X] Pull failed and could not be resolved automatically.
echo     Nothing was built, committed or published.
echo     Run this once, then re-run commit.bat:
echo         git stash -u ^&^& git pull --rebase origin !BRANCH! ^&^& git stash pop
exit /b 1

:no_git
echo [X] This folder is not a git repository.
echo     git clone https://github.com/Erawat4l/Agon.git
exit /b 1

:build_failed
echo.
echo [X] Build failed - scroll up for the first error.
exit /b 1

:push_failed
echo.
echo [X] Push failed. The branch moved on GitHub; run:
echo       git pull --rebase ^&^& commit.bat
exit /b 1

:publish_failed
echo.
echo [X] Build and push worked, publishing did not.
echo     Usually the token:  publish.bat --token github_pat_xxx
echo     Or upload Agon-!VERSION!.apk by hand on the Releases page.
exit /b 1
