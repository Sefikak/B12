@echo off
rem B12: telefon + saat APK'larini derler. Kullanim: build.bat [release]
setlocal
set "JAVA_HOME="
for /d %%d in ("%LOCALAPPDATA%\SOUNDtools\jdk\*") do set "JAVA_HOME=%%d"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
set "TASK=assembleDebug"
if /i "%~1"=="release" set "TASK=assembleRelease"
call "%~dp0gradlew.bat" --project-cache-dir "%LOCALAPPDATA%\B12build\gradle-cache" %TASK%
if errorlevel 1 exit /b 1
set "OUT=debug"
if /i "%~1"=="release" set "OUT=release"
copy /y "%LOCALAPPDATA%\B12build\app\outputs\apk\%OUT%\app-%OUT%.apk" "%~dp0B12-telefon.apk" >nul
copy /y "%LOCALAPPDATA%\B12build\wear\outputs\apk\%OUT%\wear-%OUT%.apk" "%~dp0B12-saat.apk" >nul
echo Hazir: B12-telefon.apk ve B12-saat.apk
