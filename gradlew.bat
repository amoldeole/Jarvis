@echo off
rem Use a locally installed Gradle or install Gradle 8.7 through Android Studio.
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
echo Gradle 8.7 is required. Open this project in Android Studio to provision the Gradle wrapper.
exit /b 1
