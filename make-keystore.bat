@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

REM Creates a signing key for release APKs, then writes keystore.properties
REM so build.bat release produces a signed, installable APK.
REM Both files are git-ignored. Back up arena-release.jks - losing it means
REM you cannot publish updates with the same signature.

where keytool >nul 2>nul
if errorlevel 1 (
    if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
)
where keytool >nul 2>nul
if errorlevel 1 (
    echo [X] keytool not found. Install JDK 17+ first.
    exit /b 1
)

set /p STOREPASS=Choose a keystore password: 
if "%STOREPASS%"=="" (
    echo [X] Password cannot be empty.
    exit /b 1
)

keytool -genkeypair -v ^
  -keystore arena-release.jks ^
  -alias arena ^
  -keyalg RSA -keysize 2048 -validity 10000 ^
  -storepass "%STOREPASS%" -keypass "%STOREPASS%" ^
  -dname "CN=Arena AI, OU=Mobile, O=Arena, L=Internet, C=IN"
if errorlevel 1 exit /b 1

(
  echo storeFile=arena-release.jks
  echo storePassword=%STOREPASS%
  echo keyAlias=arena
  echo keyPassword=%STOREPASS%
) > keystore.properties

echo.
echo [OK] arena-release.jks and keystore.properties created.
echo      Now run:  build.bat release
