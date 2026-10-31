@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

REM ===========================================================================
REM  Arena AI - one command after every change:
REM      1. commit + push this branch to GitHub
REM      2. build, and publish a release APK that installed apps can update from
REM
REM  Usage:
REM     commit.bat                       auto message with time
REM     commit.bat "fixed the composer"  custom message
REM     commit.bat "msg" skipbuild       commit + push only, no release
REM
REM  Set GH_TOKEN once with:  publish.bat --token github_pat_xxx
REM ===========================================================================

set "MSG=%~1"
set "MODE=%~2"
if "%MSG%"=="" set "MSG=update %DATE% %TIME:~0,5%"

echo.
echo =============================================
echo   Arena AI  ^|  commit + publish
echo =============================================
echo.

git rev-parse --is-inside-work-tree >nul 2>nul
if errorlevel 1 goto :no_git

for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD') do set "BRANCH=%%b"
echo [1/4] Branch: !BRANCH!

echo [2/4] Committing...
git add -A
git diff --cached --quiet
if not errorlevel 1 goto :nothing_to_commit
git config user.email >nul 2>nul
if errorlevel 1 git config user.email "arena@localhost"
git config user.name >nul 2>nul
if errorlevel 1 git config user.name "Arena"
git commit -m "%MSG%"
if errorlevel 1 (
    echo        commit failed - see the git message above
    exit /b 1
)
echo        committed: %MSG%
goto :push

:nothing_to_commit
echo        nothing new to commit

:push
echo [3/4] Pushing to origin !BRANCH!...
git push origin !BRANCH!
if errorlevel 1 (
    echo.
    echo [X] Push failed. Run "git pull" first if the branch moved on GitHub.
    exit /b 1
)

if /i "%MODE%"=="skipbuild" goto :done

echo [4/4] Building + publishing the release APK...
call publish.bat
if errorlevel 1 exit /b 1

:done
echo.
echo   Done. Installed apps can now update from inside the app.
exit /b 0

:no_git
echo [X] Not a git repository. Clone it first.
exit /b 1
