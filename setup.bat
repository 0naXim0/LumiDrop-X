@echo off
REM LumiDropX Build Setup — run once before first gradlew build
REM Downloads the gradle-wrapper.jar which cannot be distributed in source repositories.

set JAR=gradle\wrapper\gradle-wrapper.jar
set URL=https://github.com/gradle/gradle/raw/v8.5.0/gradle/wrapper/gradle-wrapper.jar

if exist "%JAR%" (
  echo gradle-wrapper.jar already present. Nothing to do.
  goto :end
)

echo Downloading gradle-wrapper.jar for Gradle 8.5...
if not exist "gradle\wrapper" mkdir "gradle\wrapper"

powershell -Command "try { Invoke-WebRequest -Uri '%URL%' -OutFile '%JAR%' -UseBasicParsing; Write-Host 'Done.' } catch { Write-Host 'Download failed: ' + $_.Exception.Message; exit 1 }"

if errorlevel 1 (
  echo ERROR: Download failed. Check your internet connection.
  exit /b 1
)

echo You can now run:  gradlew assembleDebug
:end
