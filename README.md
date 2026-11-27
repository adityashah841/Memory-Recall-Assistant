# Synapse Riders – Memory Recall Assistant

A fully **on-device** memory aid. Point your phone (or, in the intended product, your smart glasses)
at the person you're talking to: the app recognizes them, quietly keeps a diary of what you talked
about, and when you ask, **reminds you out loud** — *"This is Maya. Earlier today you talked about her
interview in Philadelphia."* You can then ask follow-up questions and get answers backed by the exact
moments they came from.

Built for people with early memory loss and their caregivers, where **privacy is non-negotiable**:
faces, voices and conversations **never leave the device**. No cloud, no network calls — it works in
airplane mode.

> Built in 24 hours at a **Qualcomm × Meta hackathon** in San Francisco by Team Synapse Riders,
> for Meta AI glasses + a Snapdragon phone. Since then it has gained a second AI backend so it runs
> on any recent Android phone (tested on a Pixel 10).

<!-- Demo video: add the YouTube link here once it's published. -->

---

## How it works (in one minute)

Think of it as **a diary that writes itself, plus a librarian you can ask.**

1. **The diary.** A background service glances through the camera and listens every few seconds. When
   someone is in the **center** of the frame, or someone is talking, it writes an entry: *who*, *when*,
   *where*, and *what was said*. One conversation = one entry, even if the camera drifts off their face.
2. **Recognizing people.** It only looks at the face in the middle of the frame — the person you're
   facing, not bystanders. That face becomes a 512-number "fingerprint" and is matched against people
   you've enrolled.
3. **Recall.** Press **Recall** while facing someone: the app says who they are and what you last
   talked about, suggests questions, and answers them using **only** your stored memories — showing
   the entries each answer came from, so it can't make things up.

---

## Two AI backends, one app

The app talks to its models through a single interface (`OnDeviceModelRunner`) and picks a backend at
startup — nothing else in the app changes.

| Job | Google backend (any recent Android; default) | Qualcomm backend (original hackathon target) |
| --- | --- | --- |
| Face detection + landmarks | ML Kit face detection (bundled) | MediaPipe `.pte` on ExecuTorch |
| Face identity (512-d) | FaceNet-512 on LiteRT (`assets/litert/`) | CavaFace ArcFace, QNN context binary |
| Speech-to-text | Android on-device `SpeechRecognizer` | Whisper-small, QNN context binary |
| Cues + answers | Gemini Nano (ML Kit Prompt API / AICore) | Llama 3.2 1B, QAIRT Genie |
| Voice output | Android `TextToSpeech` | Android `TextToSpeech` |

The **Qualcomm** backend is used only when its native bridge is built **and** its model files are on the
device; otherwise the **Google** backend runs. See [Qualcomm backend](#qualcomm-backend-optional).

> **Gemini Nano** (cues/answers) needs a phone that supports the ML Kit Prompt API — it works best on the
> **Pixel 10** series. On other phones everything else still works and answers fall back to a plain
> summary of the most recent memory.

---

## Quick start (Google backend)

**You need:** a computer (Windows, macOS or Linux), an Android phone running Android 12+ with
**USB debugging** on, and a USB cable. A Pixel 10 gives the full experience.

### 1. Get the toolchain

**Windows — one script, fully isolated** (installs Flutter, JDK 21, Android SDK + NDK into one folder;
nothing global is changed; delete the folder to uninstall):

```bat
git clone https://github.com/adityashah841/Memory-Recall-Assistant C:\dev\mra
cd C:\dev\mra
tools\windows\setup-flutter-env.bat
```

Then, in **every new Command Prompt window** you use for this project:

```bat
C:\dev\mra\tools\windows\activate-flutter-env.bat
```

> Keep the project at a **short path** like `C:\dev\mra` — long Windows paths break Android builds.
> Details: [`tools/windows/README.md`](tools/windows/README.md).

**macOS / Linux:** install [Flutter](https://docs.flutter.dev/get-started/install) (3.50+) and the
Android SDK (Android Studio is the easiest way), then check with `flutter doctor`.

### 2. Connect your phone

1. On the phone: **Settings → About phone → tap "Build number" 7 times** to unlock Developer options.
2. **Settings → System → Developer options → USB debugging: on.**
3. Plug it in, accept **"Allow USB debugging?"** on the phone, and check: `adb devices` lists it as
   `device`.

### 3. Run it

```bash
cd memory_recall_app
flutter pub get
flutter run --release
```

Allow **Camera, Microphone, Location and Notifications** when asked. The first build downloads a lot
and can take 5–15 minutes; later builds take well under a minute.

### 4. Try it

1. **Recall tab → Start camera.**
2. **Enroll tab → camera button**, type a name, and keep that person's face **inside the circle** for a
   few seconds.
3. Have a short conversation with them.
4. Back on **Recall**, face them and press **Recall** — the app says who they are and what you talked
   about. Tap a suggested question for an answer with evidence.

A scripted walkthrough for recording a demo is in [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md).

---

## The app

| Tab | What it's for |
| --- | --- |
| **Recall** | Live camera with the targeting circle (green box = recognized, red = unknown), the **Recall** button, spoken cue, suggested questions, answers with evidence. |
| **Timeline** | The diary — one entry per conversation (who, when, what was said). |
| **Enroll** | Register a new person from 5 centered camera frames, or name an unknown face the app has already seen. |
| **Debug** | Start/stop capture, force a capture tick, seed demo data (**replaces everything**), clear all data. |

Watch what the AI is doing (with the phone connected):

```bash
adb logcat -s ModelRuntime FaceEmbeddingEngine LiveSpeech GeminiNanoLlm RecallSpeaker CaptureLoop
```

`FaceEmbeddingEngine` prints the match score for every check — useful for tuning the face threshold
(`GoogleModelRunner.matchThreshold`, default 0.40).

---

## Architecture

```
Flutter (Dart UI) ──Pigeon──► Kotlin (Android)
  Recall · Timeline ·           bridge/     MemoryBackendChannel (Pigeon host) + DTO mappers
  Enroll · Debug                service/    MemoryForegroundService + CaptureLoop (~5 s tick)
                                capture/    CameraX · live speech · mic · location · scene change
                                ai/         OnDeviceModelRunner → GoogleModelRunner | CompositeModelRunner
                                identity/   FaceEmbeddingEngine (center face → align → embed → match)
                                recall/     retrieval → suggestions → grounded answer · cue · TTS · preview
                                data/       Room/SQLite: person_profile_table + session_table
```

- **Write path:** `CaptureLoop` → identify the center face → speech since last tick → location → save as
  known person / new unknown person / no person, merging ticks of one conversation into one entry.
- **Read path:** `RecallEngine` → identify → SQL retrieval (same person, ~75 m vicinity, most recent 5)
  → template questions → LLM answer grounded in those entries, with evidence.
- **No vector database:** identity is a linear scan over enrolled faces; memories are plain SQL.

[`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) is the full design contract (data schema, flows,
API) with a log of every implementation decision (§24).

---

## Repository layout

```
memory_recall_app/
  lib/                          Flutter UI (4 screens) + generated Pigeon bindings
  pigeons/memory_backend.dart   Flutter ↔ Kotlin API contract (source of truth)
  android/app/src/main/
    kotlin/.../memory_recall_app/   bridge · service · capture · ai · identity · recall · data · enrollment
    assets/litert/                  FaceNet-512 model (committed; see NOTICE.md)
    cpp/                            Native QNN + Genie JNI bridge (Qualcomm backend)
  android/app/src/test/          JVM unit tests
  scripts/push_models.sh         Pushes Qualcomm model weights to the device
tools/windows/                   Isolated Windows toolchain setup
docs/DEMO_SCRIPT.md              How to record the demo
IMPLEMENTATION_PLAN.md           Design contract + decision log
```

---

## Development

```bash
# Unit tests (identity geometry, recall, capture loop, transcript merging, ASR front-end)
cd memory_recall_app/android && ./gradlew :app:testDebugUnitTest      # Windows: gradlew.bat

# After editing pigeons/memory_backend.dart, regenerate the bridge (both Dart + Kotlin files):
cd memory_recall_app && dart run pigeon --input pigeons/memory_backend.dart
```

---

## Qualcomm backend (optional)

The original design runs everything on the **Snapdragon 8 Elite** NPU (Galaxy S25 Ultra):
ExecuTorch for face detection, QNN context binaries for CavaFace and Whisper, and QAIRT Genie for
Llama 3.2 1B. To enable it:

1. Get the **QAIRT 2.45 SDK** (free, Qualcomm login) and copy its arm64 runtime `.so` files into
   `android/app/src/main/jniLibs/arm64-v8a/`.
2. Set `qnnSdkRoot=/path/to/qairt/2.45.x` in `android/local.properties` and rebuild.
3. Push the model weights (~1.8 GB, not committed): `memory_recall_app/scripts/push_models.sh`.

> **Status:** the Kotlin side is complete, but the native QNN/Genie graph I/O in
> `cpp/synapse_inference.cpp` is still a skeleton and the MediaPipe `.pte` input spec is unconfirmed
> (`ExecuTorchModelRunner.INFERENCE_ENABLED = false`). See `IMPLEMENTATION_PLAN.md` §24.

---

## Limitations & roadmap

- **Who said what.** Transcripts mix both voices, so the app describes conversations as shared
  ("you talked about…"). Next step: *me-vs-not-me* separation — on glasses the wearer's voice is far
  louder at the mics, and a one-time voice enrollment makes this a small, reliable model.
- **Meta glasses.** Frames currently come from the phone's camera; relaying the glasses' camera and
  speakers to the phone is the main integration left for the intended form factor.
- **Qualcomm native bridge.** Finish the QNN/Genie graph binding (see above).
- **Face threshold** (0.40 for FaceNet) is a standard starting point; tune per device from the logs.
- **Places.** Location is GPS-only; there's no UI yet to label places ("home", "the cafe").

---

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `adb devices` shows nothing | Try another cable/port (some only charge); on Windows install the Google USB driver: `sdkmanager "extras;google;usb_driver"`, then Device Manager → Update driver. |
| `unauthorized` in `adb devices` | Accept the "Allow USB debugging?" prompt on the phone. |
| Build: *"sdkmanager did not install NDK …"* | Run `sdkmanager "ndk;<version from the error>"`, then build again. |
| Build: errors about `recallCue` / `getLivePreview` | The generated bridge is stale — run `dart run pigeon --input pigeons/memory_backend.dart`. |
| Answers start with "Most recent memory…" | Gemini Nano isn't available on this phone (or is still downloading — check `GeminiNanoLlm` in logcat). |
| No speech in the diary | Check `LiveSpeech` in logcat; make sure Microphone permission is granted. |
| No voice | Turn up **media** volume (TTS doesn't use the ringer volume). |
| `'adb' is not recognized` (Windows) | Run `tools\windows\activate-flutter-env.bat` in that window first. |

---

## Credits & licenses

- **Team Synapse Riders** — hackathon project. Code under the [MIT License](LICENSE).
- **FaceNet-512 model** — Apache-2.0, from
  [shubham0204/FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android);
  see [`assets/litert/NOTICE.md`](memory_recall_app/android/app/src/main/assets/litert/NOTICE.md).
- Google ML Kit, LiteRT and the ML Kit GenAI Prompt API (Gemini Nano) are used under Google's terms.
