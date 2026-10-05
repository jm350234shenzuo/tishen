@echo off
setlocal enabledelayedexpansion
rem ============================================================
rem  Triple-end one-click build: baicizhan + tianxuewang + youti
rem  Usage: build-all.cmd   (double-click or run from console)
rem  Output name follows each module build.gradle.kts versionName.
rem ============================================================

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
set "TOOLCHAIN=%USERPROFILE%\.android-toolchain"
set "GRADLE=%TOOLCHAIN%\gradle-dist\gradle-8.9\bin\gradle.bat"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

call :One baicizhan-a11y 1
call :One tianxuewang-a11y 2
call :One youti-a11y 3

echo [4/4] Collecting APKs to dist ...
if not exist "%ROOT%\dist" mkdir "%ROOT%\dist"
for %%m in (baicizhan-a11y tianxuewang-a11y youti-a11y) do (
  for %%f in ("%ROOT%\dist\%%m-*.apk") do del /q "%%~ff" >nul 2>nul
  for %%f in ("%ROOT%\%%m\%%m-*.apk") do copy /y "%%~ff" "%ROOT%\dist\" >nul
)
echo.
echo ==== DONE ====
dir /b "%ROOT%\dist"
echo.
endlocal
exit /b 0

:One
echo [%2/4] Building %1 ...
pushd "%ROOT%\%1"
call build.cmd
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" (
  echo [FAIL] %1 build failed, exit %RC%
  exit /b %RC%
)
exit /b 0
