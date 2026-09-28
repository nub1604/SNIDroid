@echo off
rem Builds the debug APK with the local Android SDK; output log: build.log
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME if exist "%LOCALAPPDATA%\Android\jdk-17\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Android\jdk-17"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
echo JAVA_HOME=%JAVA_HOME% > build.log
echo ANDROID_HOME=%ANDROID_HOME% >> build.log
call gradlew.bat assembleDebug --console=plain >> build.log 2>&1
echo EXITCODE=%ERRORLEVEL% >> build.log
