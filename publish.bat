@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

REM ===========================================================================
REM  Arena AI - publish a downloadable APK release to GitHub
REM
REM  Usage (in cmd, from this folder):
REM     publish.bat                 auto-bump patch:  1.1.0 -> 1.1.1
REM     publish.bat 1.2.0           publish an exact version
REM     publish.bat 1.2.0 beta      publish as a pre-release (Beta channel)
REM     publish.bat --token ghp_xx  remember a GitHub token for next time
REM
REM  Result: a GitHub Release with ArenaAI-<version>.apk attached. Every
REM  installed copy of the app sees it (Update -> Check for updates) and can
REM  download + install it, with Android still asking the user to approve.
REM ===========================================================================

set "VERSION=%~1"
set "CHANNEL=%~2"

if /i "%VERSION%"=="--token" goto :save_token
if /i "%VERSION%"=="-t" goto :save_token

echo.
echo =============================================
echo   Arena AI  ^|  publish APK release
echo =============================================
echo.

REM ---------- git state ---------------------------------------------------
where git >nul 2>nul
if errorlevel 1 goto :no_git
git rev-parse --is-inside-work-tree >nul 2>nul
if errorlevel 1 goto :no_git
for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD') do set "BRANCH=%%b"
echo [1/4] Branch: !BRANCH!

REM ---------- version -----------------------------------------------------
if "%VERSION%"=="" goto :bump_version
echo [2/4] Version: %VERSION%
goto :have_version

:bump_version
REM version.txt is the local baseline (git-ignored so pulls never conflict); the newest
REM tag is only a fallback for a clone that has never published.
set "LASTTAG="
if exist version.txt set /p LASTTAG=<version.txt
if defined LASTTAG goto :have_tag
for /f "delims=" %%t in ('git tag --list "v*" --sort=-v:refname') do if not defined LASTTAG set "LASTTAG=%%t"
if not defined LASTTAG set "LASTTAG=1.0.0"
:have_tag
set "LASTTAG=!LASTTAG:v=!"
for /f "tokens=1,2,3 delims=." %%a in ("!LASTTAG!") do (
    set "MAJ=%%a"
    set "MIN=%%b"
    set "PAT=%%c"
)
if not defined PAT set "PAT=0"
set /a "PAT+=1"
set "VERSION=!MAJ!.!MIN!.!PAT!"
echo [2/4] Version: !VERSION! ^(auto-bump from !LASTTAG!^)
goto :have_version

:have_version
if "!CHANNEL!"=="" set "CHANNEL=stable"

REM ---------- token -------------------------------------------------------
set "TOKEN="
if exist "gh-token.txt" set /p TOKEN=<gh-token.txt
if defined GH_TOKEN set "TOKEN=!GH_TOKEN!"
if defined GITHUB_TOKEN set "TOKEN=!GITHUB_TOKEN!"
if not defined TOKEN goto :need_token
echo [3/4] Token: found
goto :publish

:need_token
echo.
echo [3/4] A GitHub token is needed once, to upload the release.
echo.
echo     Create one (2 minutes):
echo       1. Open https://github.com/settings/personal-access-tokens/new
echo       2. Repository access: Only select repositories . Arena-Ai-App
echo       3. Permissions: Contents ^= Read and write
echo       4. Generate, copy the token, then run:  publish.bat --token YOUR_TOKEN
echo.
set /p TOKEN=Paste the token now ^(or press Enter to cancel^): 
if "!TOKEN!"=="" exit /b 1
> gh-token.txt echo !TOKEN!

:publish
echo [4/4] Building and uploading...
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\publish.ps1" ^
    -Version "!VERSION!" -Token "!TOKEN!" -Channel "!CHANNEL!"
if errorlevel 1 goto :failed

echo.
> version.txt echo !VERSION!
echo Local copy: %~dp0ArenaAI-!VERSION!.apk
echo Apps already installed get it via Update -^> Check for updates.
exit /b 0

REM ---------- helpers -----------------------------------------------------
:save_token
set "TOKEN=%~2"
if "%TOKEN%"=="" (
    echo Usage: publish.bat --token YOUR_GITHUB_TOKEN
    exit /b 1
)
> gh-token.txt echo %TOKEN%
echo Token saved to gh-token.txt ^(git-ignored^). Next: publish.bat
exit /b 0

:no_git
echo [X] This folder is not a git repository.
echo     Clone it first:  git clone -b arena/0b045796-arena-ai-app https://github.com/Erawat4l/Arena-Ai-App.git
exit /b 1

:failed
echo.
echo [X] Publish failed. Common causes:
echo       - Token lacks "Contents: read and write" on Erawat4l/Arena-Ai-App
echo       - No internet / proxy blocking api.github.com
echo       - The Gradle build itself failed (scroll up)
exit /b 1
