@echo off
REM Agon - Windows launcher. Everything real happens in build.py, so the phone and the PC
REM run the same logic; this file only finds Python and starts it.
REM
REM   build.bat              build the APK
REM   build.bat doctor       what is installed / what is missing (builds nothing)
REM   build.bat apk 1.4.58   build that exact version
REM   build.bat desktop      APK + both Windows installers
REM   build.bat publish      APK + installers + upload the release
REM   build.bat sign         fix signing: find/adopt/create the key, print its fingerprint
setlocal EnableExtensions
cd /d "%~dp0"

REM The Windows launcher: the Microsoft Store build of Python is a "python.exe" stub that
REM opens the Store instead of running anything, so each candidate is RUN, not just found.
set "PY="
where py >nul 2>nul
if not errorlevel 1 (
    py -3 -c "import sys" >nul 2>nul
    if not errorlevel 1 set "PY=py -3"
)
if not defined PY (
    where python >nul 2>nul
    if not errorlevel 1 (
        python -c "import sys" >nul 2>nul
        if not errorlevel 1 set "PY=python"
    )
)
if not defined PY (
    if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
    if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
)
if not defined PY (
    echo Python 3 is needed to run the build - installing it once.
    where winget >nul 2>nul
    if errorlevel 1 (
        echo [X] Neither Python nor winget was found.
        echo     Install Python 3.12 from python.org, then run build.bat again.
        exit /b 1
    )
    winget install -e --id Python.Python.3.12 --accept-package-agreements --accept-source-agreements
    REM A fresh PATH is not visible to this window, so use the path winget just used.
    if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
    if not defined PY set "PY=python"
)

%PY% "%~dp0build.py" %*
exit /b %errorlevel%
