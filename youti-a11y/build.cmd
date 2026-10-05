@echo off
setlocal enabledelayedexpansion
rem ============================================================
rem  Youti A11y -- LSPosed module for com.ytw.app
rem  One-click signed APK build.  Usage: build.cmd
rem  Needs: JDK 21 + Android SDK 35 + Gradle 8.9 (local toolchain)
rem ============================================================

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
set "TOOLCHAIN=%USERPROFILE%\.android-toolchain"
set "GRADLE=%TOOLCHAIN%\gradle-dist\gradle-8.9\bin\gradle.bat"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "HERE=%~dp0"
set "HERE=%HERE:~0,-1%"
cd /d "%HERE%"

echo [1/3] checking toolchain ...
if not exist "%GRADLE%" (
  echo   ERROR: gradle not found at %GRADLE%
  exit /b 1
)
if not exist "%TOOLCHAIN%\sdk" (
  echo   ERROR: android sdk not found at %TOOLCHAIN%\sdk
  exit /b 1
)

echo [2/3] building release APK ...
call "%GRADLE%" --no-daemon -p "%HERE%" assembleRelease
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
  echo [3/3] build FAILED, exit code %RC%
  exit /b %RC%
)

set "APK=%HERE%\app\build\outputs\apk\release\app-release.apk"
set "VER="
for /f "tokens=3" %%v in ('findstr /r /c:"versionName" "%HERE%\app\build.gradle.kts"') do set "VER=%%v"
set VER=%VER:"=%
if "%VER%"=="" set "VER=unknown"
set "OUT=%HERE%\youti-a11y-%VER%.apk"
if not exist "%APK%" (
  echo [3/3] APK not found at %APK%
  exit /b 1
)
copy /y "%APK%" "%OUT%" >nul
for %%f in ("%HERE%\youti-a11y-*.apk") do if /i not "%%~nxf"=="youti-a11y-%VER%.apk" del /q "%%~ff" >nul 2>nul
echo [3/3] done.
echo   %OUT%
endlocal
exit /b 0
