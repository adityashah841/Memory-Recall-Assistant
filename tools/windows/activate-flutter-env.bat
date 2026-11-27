@echo off
rem Turns on the isolated Flutter + Android toolchain for the CURRENT Command Prompt window only.
rem Close the window to "deactivate". Nothing global is changed.
rem
rem Usage:
rem   activate-flutter-env.bat                      (env at C:\dev\flutter-env)
rem   activate-flutter-env.bat D:\envs\flutter-mra  (custom location)
rem
rem Deliberately no "setlocal" here: the variables must stay set in the calling window.

set "FENV_ROOT=%~1"
if "%FENV_ROOT%"=="" set "FENV_ROOT=C:\dev\flutter-env"

if not exist "%FENV_ROOT%\flutter\bin\flutter.bat" (
    echo No Flutter env at %FENV_ROOT% - run setup-flutter-env.bat first.
    exit /b 1
)

for /d %%D in ("%FENV_ROOT%\jdk\jdk-*") do set "JAVA_HOME=%%D"

rem Toolchain locations
set "ANDROID_HOME=%FENV_ROOT%\android-sdk"
set "ANDROID_SDK_ROOT=%FENV_ROOT%\android-sdk"

rem Keep caches/config inside the env so deleting the folder removes them too
set "PUB_CACHE=%FENV_ROOT%\pub-cache"
set "GRADLE_USER_HOME=%FENV_ROOT%\gradle-home"
set "ANDROID_USER_HOME=%FENV_ROOT%\android-user-home"

set "PATH=%FENV_ROOT%\flutter\bin;%PUB_CACHE%\bin;%JAVA_HOME%\bin;%FENV_ROOT%\git\cmd;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\cmdline-tools\latest\bin;%PATH%"

echo Flutter env active (%FENV_ROOT%) for this window. Close the window to deactivate.
exit /b 0
