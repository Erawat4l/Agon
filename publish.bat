@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

REM ===========================================================================
REM  Agon - publish a downloadable APK release to GitHub
REM
REM  Usage (in cmd, from this folder):
REM     publish.bat                 auto-bump patch:  1.1.0 -> 1.1.1
REM     publish.bat 1.2.0           publish an exact version
REM     publish.bat 1.2.0 beta      publish as a pre-release (Beta channel)
REM     publish.bat local           build the APK + installers and STOP - no release,
REM                                 no upload, GitHub never contacted
REM     publish.bat 1.2.0 local     same, at a version you choose
REM     publish.bat --token ghp_xx  remember a GitHub token for next time
REM
REM  Result: a GitHub Release with Agon-<version>.apk attached. Every
REM  installed copy of the app sees it (Update -> Check for updates) and can
REM  download + install it, with Android still asking the user to approve.
REM ===========================================================================

set "VERSION=%~1"
set "CHANNEL=%~2"
set "LOCAL=0"

if /i "%VERSION%"=="--token" goto :save_token
if /i "%VERSION%"=="-t" goto :save_token

REM "local" is a mode, not a version. Accepted in either position, so both
REM `publish.bat local` and `publish.bat 1.4.45 local` mean the same thing.
if /i "%VERSION%"=="local" (
    set "LOCAL=1"
    REM `publish.bat local 1.4.46` must mean the same as the other order.
    set "VERSION=%CHANNEL%"
    set "CHANNEL="
)
if /i "%CHANNEL%"=="local" (
    set "LOCAL=1"
    set "CHANNEL="
)

echo.
echo =============================================
echo   Agon  ^|  publish APK release
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
REM The next version must be higher than BOTH the newest git tag and the last version
REM published from this folder, whichever is higher. version.txt alone is not enough: a
REM stale copy of it once took a project from 1.4.29 straight back to 1.0.2, and a release
REM with a LOWER versionCode than the installed app is invisible for ever - releases/latest
REM only ever offers something newer, so the app stops finding updates entirely.
set "TAGTAG="
for /f "delims=" %%t in ('git tag --list "v*" --sort=-v:refname') do if not defined TAGTAG set "TAGTAG=%%t"
if defined TAGTAG set "TAGTAG=!TAGTAG:v=!"

set "FILETAG="
if exist version.txt set /p FILETAG=<version.txt
if defined FILETAG set "FILETAG=!FILETAG:v=!"

REM Highest wins, compared as major*1000000+minor*1000+patch.
set "LASTTAG="
set "TAGN=0"
set "FILEN=0"
if defined TAGTAG call :version_code TAGTAG TAGN
if defined FILETAG call :version_code FILETAG FILEN
if !TAGN! GEQ !FILEN! (
    set "LASTTAG=!TAGTAG!"
) else (
    set "LASTTAG=!FILETAG!"
)
if not defined LASTTAG set "LASTTAG=1.0.0"
set "TAGTAG="
set "FILETAG="
:have_tag
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
set "TOKENSRC="
if exist "gh-token.txt" set /p TOKEN=<gh-token.txt
if defined TOKEN set "TOKENSRC=gh-token.txt"
if defined GH_TOKEN (
    set "TOKEN=!GH_TOKEN!"
    set "TOKENSRC=GH_TOKEN"
)
if defined GITHUB_TOKEN (
    set "TOKEN=!GITHUB_TOKEN!"
    set "TOKENSRC=GITHUB_TOKEN"
)
REM Last resort: reuse the credential the GitHub CLI is already signed in with.
if not defined TOKEN (
    where gh >nul 2>nul
    if not errorlevel 1 (
        for /f "usebackq delims=" %%T in (`gh auth token 2^>nul`) do set "TOKEN=%%T"
        if defined TOKEN set "TOKENSRC=the GitHub CLI login (gh auth token)"
    )
)
if "!LOCAL!"=="1" goto :publish
if not defined TOKEN goto :need_token
echo [3/4] Token: from !TOKENSRC!
if "!TOKENSRC!"=="gh-token.txt" echo       ^(delete gh-token.txt to use the GitHub CLI login instead^)
goto :publish

:need_token
echo.
echo [3/4] A GitHub token is needed once, to upload the release.
echo.
echo     Create one (2 minutes):
echo       1. Open https://github.com/settings/personal-access-tokens/new
echo          ^(or delete gh-token.txt if an old token is stored there^)
echo       2. Repository access: Only select repositories . Agon
echo       3. Permissions: Contents ^= Read and write
echo       4. Generate, copy the token, then run:  publish.bat --token YOUR_TOKEN
echo.
set /p TOKEN=Paste the token now ^(or press Enter to cancel^): 
if "!TOKEN!"=="" exit /b 1
> gh-token.txt echo !TOKEN!

:publish
echo [4/4] Building and uploading...
REM One line per call, on purpose: a "^" line continuation inside a parenthesised
REM block is a documented cmd.exe trap (the block is parsed before the caret is honoured).
if "!LOCAL!"=="1" powershell -NoProfile -ExecutionPolicy Bypass -File "tools\publish.ps1" -Version "!VERSION!" -Channel "!CHANNEL!" -LocalOnly
if not "!LOCAL!"=="1" powershell -NoProfile -ExecutionPolicy Bypass -File "tools\publish.ps1" -Version "!VERSION!" -Token "!TOKEN!" -Channel "!CHANNEL!"
if errorlevel 1 goto :failed

echo.
if "!LOCAL!"=="1" goto :local_done
> version.txt echo !VERSION!
echo Local copy: %~dp0Agon-!VERSION!.apk
echo Apps already installed get it via Update -^> Check for updates.
exit /b 0

:local_done
REM version.txt is NOT written: nothing was published, so the next real publish must not
REM believe this number is already taken.
echo Local build finished. Nothing was published and no release was created.
echo   APK        : %~dp0Agon-!VERSION!.apk
echo   Installers : %~dp0desktop\release\Agon-Setup-64.exe
echo                %~dp0desktop\release\Agon-Setup-32.exe
exit /b 0

REM ---------- helpers -----------------------------------------------------
REM :version_code <version> <out-var>  -> sets <out-var> to a.b.c as major*1e6+minor*1e3+patch
:version_code
set "_v=!%~1!"
set "_a=0"
set "_b=0"
set "_c=0"
for /f "tokens=1,2,3 delims=." %%a in ("!_v!") do (
    set "_a=%%a"
    set "_b=%%b"
    set "_c=%%c"
)
if not defined _b set "_b=0"
if not defined _c set "_c=0"
set /a "%~2=_a*1000000+_b*1000+_c"
goto :eof

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
echo     Clone it first:  git clone https://github.com/Erawat4l/Agon.git
exit /b 1

:failed
echo.
echo [X] Publish failed. Common causes:
echo       - Token lacks "Contents: read and write" on Erawat4l/Agon
echo       - No internet / proxy blocking api.github.com
echo       - The Gradle build itself failed (scroll up)
exit /b 1
