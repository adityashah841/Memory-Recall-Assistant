# Windows toolchain (isolated)

Two Command Prompt scripts that install everything needed to build the app **into one folder**
(default `C:\dev\flutter-env`), without touching your global PATH or other installs.

| Script | Run | What it does |
| --- | --- | --- |
| `setup-flutter-env.bat [ROOT]` | once | Downloads Flutter (latest stable), JDK 21 (Temurin), portable Git (only if you don't have Git), Android command-line tools; installs platform-tools, Android 36 platform, build-tools and the NDK version Flutter needs; accepts SDK licenses; runs `flutter doctor -v`. |
| `activate-flutter-env.bat [ROOT]` | in each new Command Prompt window | Puts that toolchain on PATH and points `JAVA_HOME`, `ANDROID_HOME`, `PUB_CACHE` and `GRADLE_USER_HOME` into the folder — for that window only. Close the window to deactivate. |

Uses only built-in Windows tools (`curl`, `tar`, `findstr`); no PowerShell. Needs about 7 GB.

**Uninstall:** close any windows using it and delete the folder (plus a few KB of Flutter/Dart settings
in `%APPDATA%\.flutter*` and `%LOCALAPPDATA%\.dartServer` if you want them gone too).

## Notes

- Keep both the env folder and the project at **short paths without spaces** (e.g. `C:\dev\...`).
- If Android Studio is installed, Flutter may use Android Studio's bundled Java instead of the one in
  the folder. That's fine.
- `flutter doctor` warnings about **Visual Studio** (only for Windows desktop apps) and
  **"Get-Process failed"** don't affect Android builds.
- Google's `sdkmanager` now forwards to the new "Android CLI" and prints deprecation warnings; it still
  works. Gradle can't auto-download the NDK through it, which is why setup installs the NDK itself.
