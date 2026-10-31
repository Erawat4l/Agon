@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

REM ===========================================================================
REM  Agon - rename the GitHub repository and point the project at it
REM
REM  Usage:
REM     rename-repo.bat Agon              rename to Erawat4l/Agon
REM     rename-repo.bat myname/Agon       rename and move to another owner
REM
REM  What it does:
REM     1. GitHub: renames the repository (old links keep redirecting, and
REM        electron-updater follows redirects, so installed copies still update)
REM     2. git: releases the old url from "origin" and sets the new one
REM     3. sources: updates REPO_OWNER / REPO_NAME in the app, the desktop client,
REM        the release scripts and the docs  (python tools\set-repo.py)
REM
REM  Needs: the GitHub CLI (winget install GitHub.cli) and, for step 1, a token
REM  with "Administration" write on the repository:
REM     gh auth login
REM  If step 1 is refused, do it by hand: GitHub -> Settings -> Repository name.
REM  Nothing is deleted and no version is bumped.
REM ===========================================================================

set "TARGET=%~1"
if "%TARGET%"=="" goto :usage

if not exist "app\src\main\java\ai\arena\webapp\UpdateManager.java" goto :wrong_folder

echo.
echo =============================================
echo   Agon  ^|  rename the repository to %TARGET%
echo =============================================
echo.

REM The owner/name the code currently uses, read by the same script that changes it.
set "OLD_OWNER="
set "OLD_REPO="
for /f "usebackq tokens=1,2 delims=/" %%a in (`python tools\set-repo.py --current`) do (
    set "OLD_OWNER=%%a"
    set "OLD_REPO=%%b"
)
if not defined OLD_OWNER goto :no_python
if not defined OLD_REPO goto :no_python
echo Current: !OLD_OWNER!/!OLD_REPO!

REM Split the requested name into owner and repo.
set "NEW_OWNER=!OLD_OWNER!"
set "NEW_REPO=%TARGET%"
echo %TARGET% | find "/" >nul
if not errorlevel 1 for /f "tokens=1,2 delims=/" %%a in ("%TARGET%") do (
    set "NEW_OWNER=%%a"
    set "NEW_REPO=%%b"
)
if not defined NEW_REPO goto :usage
echo Target : !NEW_OWNER!/!NEW_REPO!
echo.

echo [1/3] GitHub...
where gh >nul 2>nul
if errorlevel 1 goto :no_gh
gh repo rename !NEW_REPO! --repo !OLD_OWNER!/!OLD_REPO! --yes
if errorlevel 1 goto :gh_failed
echo        renamed on GitHub.
goto :git_remote

:no_gh
echo        [i] GitHub CLI not found - rename it by hand:
echo            GitHub -^> the repository -^> Settings -^> Repository name -^> !NEW_REPO!
echo            Install the CLI with:  winget install GitHub.cli
goto :git_remote

:gh_failed
echo        [i] GitHub refused the rename. Do it by hand in Settings, then run this again.
goto :git_remote

:git_remote
echo [2/3] git remote...
git remote set-url origin https://github.com/!NEW_OWNER!/!NEW_REPO!.git >nul 2>nul
if errorlevel 1 goto :no_git
echo        origin -^> https://github.com/!NEW_OWNER!/!NEW_REPO!.git

echo [3/3] sources + scripts...
python tools\set-repo.py !NEW_OWNER!/!NEW_REPO!
if errorlevel 1 goto :summary

:summary
echo.
echo =============================================
echo   [DONE]
echo =============================================
echo   1. Rebuild so the app and installers carry the new name:  build-all.bat
echo   2. Later pushes go to:  https://github.com/!NEW_OWNER!/!NEW_REPO!
echo =============================================
echo.
exit /b 0

:no_git
echo [X] git remote set-url failed - is this a git clone?
exit /b 1

:no_python
echo [X] Python is needed to read and rewrite REPO_OWNER / REPO_NAME. Install it, then run:
echo       python tools\set-repo.py !NEW_OWNER!/!NEW_REPO!
exit /b 1

:wrong_folder
echo [X] Run this from the project folder (the one with app\ and desktop\).
exit /b 1

:usage
echo Usage: rename-repo.bat NEWNAME
echo        rename-repo.bat OWNER/NEWNAME
exit /b 1
