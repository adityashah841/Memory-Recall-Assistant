@echo off
rem One-time setup of an ISOLATED Flutter + Android toolchain for the Memory Recall Assistant.
rem
rem Everything (Flutter SDK, JDK, Android SDK, Gradle/pub caches, and Git if you don't have it)
rem goes inside ONE folder. Nothing is added to your global PATH. Use activate-flutter-env.bat in
rem a Command Prompt window to switch it on; closing that window switches it off. Delete the
rem folder to remove everything.
rem
rem Usage (Command Prompt, in the folder holding both .bat files):
rem   tools\windows\setup-flutter-env.bat                      (installs to C:\dev\flutter-env)
rem   tools\windows\setup-flutter-env.bat D:\envs\flutter-mra  (custom location - short, no spaces)
rem
rem Needs ~7 GB of disk. Uses only curl.exe, tar.exe and findstr (built into Windows 10/11).

setlocal EnableExtensions
set "ROOT=%~1"
if "%ROOT%"=="" set "ROOT=C:\dev\flutter-env"
set "DL=%ROOT%\_downloads"
set "SDK=%ROOT%\android-sdk"

where curl >nul 2>&1 || (echo ERROR: curl.exe not found. Needs Windows 10 1803 or newer. & exit /b 1)
where tar  >nul 2>&1 || (echo ERROR: tar.exe not found. Needs Windows 10 1803 or newer. & exit /b 1)
if not exist "%DL%" mkdir "%DL%"

rem ---------------------------------------------------------------- 1. Git (Flutter needs it)
if exist "%ROOT%\git\cmd\git.exe" (echo [1/5] Portable Git already present & goto git_done)
where git >nul 2>&1 && (echo [1/5] Git already installed on this PC - using it & goto git_done)
echo [1/5] Portable Git (MinGit)
rem "releases/latest" redirects to .../tag/vX.Y.Z.windows.N - read the tag from that redirect.
set "GIT_LOC="
for /f "tokens=2" %%L in ('curl -sI https://github.com/git-for-windows/git/releases/latest ^| findstr /i /b "location:"') do set "GIT_LOC=%%L"
if "%GIT_LOC%"=="" (echo ERROR: could not find the latest Git for Windows release. & exit /b 1)
for %%T in ("%GIT_LOC%") do set "GIT_TAG=%%~nxT"
rem v2.52.0.windows.1 -> MinGit-2.52.0-64-bit.zip ; v2.52.0.windows.2 -> MinGit-2.52.0.2-64-bit.zip
set "GIT_V=%GIT_TAG:~1%"
set "GIT_V=%GIT_V:.windows.=;%"
for /f "tokens=1,2 delims=;" %%a in ("%GIT_V%") do (set "GIT_BASE=%%a" & set "GIT_N=%%b")
set "GIT_ZIP=MinGit-%GIT_BASE%-64-bit.zip"
if not "%GIT_N%"=="1" set "GIT_ZIP=MinGit-%GIT_BASE%.%GIT_N%-64-bit.zip"
echo   %GIT_TAG% / %GIT_ZIP%
curl -L --fail -o "%DL%\%GIT_ZIP%" "https://github.com/git-for-windows/git/releases/download/%GIT_TAG%/%GIT_ZIP%" || exit /b 1
mkdir "%ROOT%\git" 2>nul
tar -xf "%DL%\%GIT_ZIP%" -C "%ROOT%\git" || exit /b 1
:git_done

rem ---------------------------------------------------------------- 2. JDK 21 (Eclipse Temurin)
if exist "%ROOT%\jdk\jdk-*" (echo [2/5] JDK already present & goto jdk_done)
echo [2/5] JDK 21 (Eclipse Temurin)
curl -L --fail -o "%DL%\temurin-jdk21.zip" "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk" || exit /b 1
mkdir "%ROOT%\jdk" 2>nul
tar -xf "%DL%\temurin-jdk21.zip" -C "%ROOT%\jdk" || exit /b 1
:jdk_done

rem ---------------------------------------------------------------- 3. Flutter SDK (latest stable)
if exist "%ROOT%\flutter\bin\flutter.bat" (echo [3/5] Flutter already present & goto flutter_done)
echo [3/5] Flutter SDK (stable)
curl -sL --fail -o "%DL%\releases_windows.json" "https://storage.googleapis.com/flutter_infra_release/releases/releases_windows.json" || exit /b 1
rem Releases are listed newest first; take the first stable Windows archive.
set "FLINE="
for /f "delims=" %%A in ('findstr /c:"stable/windows/flutter_windows_" "%DL%\releases_windows.json"') do if not defined FLINE set "FLINE=%%A"
if not defined FLINE (echo ERROR: could not find the Flutter download. & exit /b 1)
set "FLINE=%FLINE:*stable/windows/=%"
set FNAME=%FLINE:"=%
set FNAME=%FNAME:,=%
if /i not "%FNAME:~-11%"=="-stable.zip" (echo ERROR: unexpected Flutter archive name: %FNAME% & exit /b 1)
echo   %FNAME%
curl -L --fail -o "%DL%\%FNAME%" "https://storage.googleapis.com/flutter_infra_release/releases/stable/windows/%FNAME%" || exit /b 1
rem The zip contains a top-level "flutter" folder.
tar -xf "%DL%\%FNAME%" -C "%ROOT%" || exit /b 1
:flutter_done

rem ---------------------------------------------------------------- 4. Android SDK command-line tools
if exist "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" (echo [4/5] Android command-line tools already present & goto sdk_done)
echo [4/5] Android SDK command-line tools
curl -sL --fail -o "%DL%\repository2-3.xml" "https://dl.google.com/android/repository/repository2-3.xml" || exit /b 1
rem Pick the highest-numbered commandlinetools-win-<N>_latest.zip listed.
set /a CLT_MAX=0
for /f "delims=" %%A in ('findstr /c:"commandlinetools-win-" "%DL%\repository2-3.xml"') do call :clt_candidate "%%A"
if %CLT_MAX%==0 (echo ERROR: could not find the Android command-line tools download. & exit /b 1)
set "CLT_ZIP=commandlinetools-win-%CLT_MAX%_latest.zip"
echo   %CLT_ZIP%
curl -L --fail -o "%DL%\%CLT_ZIP%" "https://dl.google.com/android/repository/%CLT_ZIP%" || exit /b 1
if exist "%DL%\clt-tmp" rmdir /s /q "%DL%\clt-tmp"
mkdir "%DL%\clt-tmp"
tar -xf "%DL%\%CLT_ZIP%" -C "%DL%\clt-tmp" || exit /b 1
mkdir "%SDK%\cmdline-tools" 2>nul
move "%DL%\clt-tmp\cmdline-tools" "%SDK%\cmdline-tools\latest" >nul || exit /b 1
rmdir /s /q "%DL%\clt-tmp"
:sdk_done

rem ---------------------------------------------------------------- 5. Packages, licenses, check
echo [5/5] Installing Android packages + accepting licenses
rem Activate inside this script only (setlocal above keeps it from leaking out).
call "%~dp0activate-flutter-env.bat" "%ROOT%" || exit /b 1

rem Accept all SDK licenses non-interactively (Gradle needs this to auto-download build tools/NDK).
(for /l %%i in (1,1,30) do @echo y) | "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" --licenses >nul
call "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-36" "build-tools;36.0.0" || exit /b 1

rem The NDK Flutter's Gradle plugin asks for. Gradle can't auto-download it through the new Android
rem CLI (it mangles "ndk;<version>"), so install it here. The version is read from the Flutter SDK.
set "NDK_VER="
for /f tokens^=2^ delims^=^" %%V in ('findstr /c:"val ndkVersion" "%ROOT%\flutter\packages\flutter_tools\gradle\src\main\kotlin\FlutterExtension.kt"') do set "NDK_VER=%%V"
if "%NDK_VER%"=="" (
    echo WARNING: could not read Flutter's NDK version - the first build may fail to find the NDK.
) else (
    echo   NDK %NDK_VER%
    call "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" "ndk;%NDK_VER%"
    if not exist "%SDK%\ndk\%NDK_VER%\source.properties" (
        echo WARNING: NDK %NDK_VER% did not install. See README "Troubleshooting" for the manual steps.
    )
)

call flutter --disable-analytics >nul
call flutter precache --android
call flutter doctor -v

echo.
echo Done. In any new Command Prompt window, run:
echo   "%~dp0activate-flutter-env.bat" %ROOT%
echo To remove everything later: close terminals using it, then delete %ROOT%
endlocal
exit /b 0

rem ---------------------------------------------------------------- helpers
:clt_candidate
rem %1 is an XML line containing commandlinetools-win-<N>_latest.zip ; keep the largest N.
set "CLT_LINE=%~1"
set "CLT_LINE=%CLT_LINE:*commandlinetools-win-=%"
for /f "delims=_" %%N in ("%CLT_LINE%") do set "CLT_N=%%N"
set /a CLT_N=CLT_N 2>nul
if %CLT_N% GTR %CLT_MAX% set /a CLT_MAX=CLT_N
exit /b 0
