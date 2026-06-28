# Synapse Riders — Memory Recall Assistant

A fully **on-device** memory-recall assistant for the **Samsung Galaxy S25 Ultra (Snapdragon 8 Elite)**.
Point the camera at the person you're talking to; the app recognizes them, recalls your shared history,
and answers questions about past conversations — **100% locally, no cloud, works in airplane mode.**

Built for people who need a memory aid (e.g. early-stage memory loss) and their caregivers, where
**privacy is non-negotiable**: faces, audio, and conversations never leave the device.

---

## What it does

- **Center-crop targeting** — identifies the face closest to the frame center (the person you're
  intentionally talking to), not random bystanders.
- **On-device identity** — CavaFace ArcFace embeddings (512-d) matched by cosine similarity.
- **Always-live capture** — a foreground service samples camera + microphone + location every ~5 s,
  persisting only *meaningful* events (a face, speech, or a scene change) into a local knowledge graph.
- **Recall** — press Recall on a person → suggested questions → grounded answers with **cited
  evidence** (the exact past sessions that back the answer).
- **Enrollment** — capture 5 centered frames to register a new person.
- **Speech** — Whisper transcribes the rolling 30 s audio window on-device.
- **Language** — Llama 3.2 1B (via QAIRT Genie) phrases recall cues and answers.

Everything degrades gracefully: a missing model, permission, or sensor never crashes the app.

---

## On-device AI stack (Snapdragon 8 Elite NPU)

Three on-device runtimes — all running on the Hexagon NPU:

| Purpose | Model | Format / Runtime |
| --- | --- | --- |
| Face detection + landmark | MediaPipe | **ExecuTorch** `.pte` |
| Face embedding (identity) | **CavaFace** (ArcFace, w8a16) | **QNN context binary** `.bin` |
| Speech-to-text | **Whisper-small** (w8a16) | **QNN context binary** `.bin` |
| LLM (cues + answers) | **Llama 3.2 1B Instruct** (w4) | **QAIRT Genie** bundle |

> No cloud inference, no network calls at runtime. Retrieval is plain SQL (person + location + recency)
> — no vector DB needed at this scale.

See [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) for the full architecture, data schema, and the
per-checkpoint build log (§24 records every implementation decision and deviation).

---

## Architecture

```
Flutter (Dart UI)  ──Pigeon──►  Kotlin (Android)
  Recall / Timeline / Enroll / Debug        bridge/   Pigeon host
                                            service/  MemoryForegroundService + CaptureLoop (~5s)
                                            capture/  CameraX · mic · location · scene-change
                                            ai/       OnDeviceModelRunner → ExecuTorch | QNN | Genie
                                            identity/ FaceEmbeddingEngine (center-crop + match)
                                            recall/   retrieval → suggestions → grounded answer
                                            data/     Room (person_profile + session tables)
```

- **Write path:** `CaptureLoop` → identify center face → transcript + location → insert Case A
  (known) / B (unknown) / C (no person).
- **Read path:** `RecallEngine` → identify → SQL retrieval (75 m vicinity, recency, top-5) →
  template suggestions → grounded answer + evidence.

---

## Repository layout

```
memory_recall_app/
  lib/                     Flutter UI (4 screens) + Pigeon bindings
  android/app/src/main/kotlin/com/synapseriders/memory_recall_app/
    bridge/ service/ capture/ ai/ identity/ recall/ data/ enrollment/
  android/app/src/main/cpp/   Native QNN + Genie JNI bridge (CMake)
  pigeons/memory_backend.dart Flutter↔Kotlin API contract
  scripts/push_models.sh      Deploy model weights to the device
IMPLEMENTATION_PLAN.md     The build contract (v4.1)
```

---

## Build & run (Samsung S25 Ultra)

**Prerequisites:** Flutter 3.44+, Android SDK (API 35) + JDK 17, a Snapdragon 8 Elite device with USB
debugging.

```bash
cd memory_recall_app
flutter pub get
flutter run            # builds + installs on the connected S25
```
Grant Camera / Microphone / Location / Notifications on first launch.

**Model weights** (~1.8 GB) are **not** committed (size + GitHub limits). Push them to the device:
```bash
scripts/push_models.sh        # adb-pushes .pte + .bin + Genie bundle to the app's files dir
```

### Enabling the NPU models
The heavy models run via Qualcomm's runtime, which needs the **QAIRT 2.45 SDK** (free, Qualcomm login):

1. Copy the QAIRT `arm64` `.so` libs into `android/app/src/main/jniLibs/arm64-v8a/`
   (`libQnnHtp.so`, `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so`, `libQnnSystem.so`, `libGenie.so`, …).
2. Set `qnnSdkRoot=/path/to/qairt/2.45.x` in `android/local.properties`.
3. Rebuild — the native bridge (`cpp/`) compiles and CavaFace / Whisper / LLM activate.

ExecuTorch (face detection/landmark) is pulled automatically from Maven Central — no manual step.

```bash
# Run the unit-test suite (identity, recall, capture, ASR front-end)
cd android && ./gradlew :app:testDebugUnitTest
```

---

## Status

**Working & demoable on-device today** (verified on the S25 Ultra):
- App + 4-screen UI, foreground capture service, ~5 s tick loop persisting events.
- Recall pipeline: person identification → suggested questions → grounded answer with cited evidence.
- Enrollment flow, demo seeding, Room knowledge graph, graceful fallbacks.
- ExecuTorch models load on-device; 32 unit tests green.

**Pending external artifacts** (not code — see `IMPLEMENTATION_PLAN.md` §24):
- **QAIRT 2.45 `.so` libs** → enable CavaFace / Whisper / Genie-LLM execution (until then those paths
  use deterministic fallbacks).
- **MediaPipe `.pte` I/O spec** → re-enable ExecuTorch detection (`ExecuTorchModelRunner.INFERENCE_ENABLED`).

---

## Tech highlights

- **Privacy-first, fully on-device** GenAI on the Snapdragon 8 Elite NPU.
- **Three on-device runtimes** orchestrated behind one `OnDeviceModelRunner` interface.
- **Intentional targeting** via center-crop face selection.
- **Grounded answers** — the LLM only rewrites retrieved facts; every answer cites its source rows.
- Clean Flutter↔Kotlin separation via **Pigeon**, with the model layer fully swappable.

---

*Hackathon project · Team Synapse Riders.*
