# cpp — native QNN + Genie bridge (`libsynapse_inference.so`)

Implements the JNI entry points in `ai/NativeInference.kt` for the Qualcomm runtimes that have no JVM
API: CavaFace + Whisper (raw QNN context binaries) and the Llama 3.2 1B Genie dialog
(IMPLEMENTATION_PLAN.md §2, §8.2, §22 CP3).

## Enabling the native build
1. Install **QAIRT 2.45.0** (Qualcomm AI Engine Direct SDK) — must match the binaries' build version.
2. Point Gradle at it (one of):
   - add `qnnSdkRoot=/abs/path/to/qairt/2.45.0.xxxx` to `android/local.properties`, **or**
   - export `QNN_SDK_ROOT=/abs/path/...` before building.
3. Copy the arm64 runtime `.so` libs into `android/app/src/main/jniLibs/arm64-v8a/`:
   - from `$QNN_SDK_ROOT/lib/aarch64-android/`: `libQnnHtp.so`, `libQnnHtpV79Stub.so`,
     `libQnnHtpPrepare.so`, `libQnnSystem.so`, `libGenie.so` (+ optional `libQnnGpu.so`/`libQnnCpu.so`)
   - from `$QNN_SDK_ROOT/lib/hexagon-v79/unsigned/`: `libQnnHtpV79Skel.so`
4. Build normally (`flutter run`). Without steps 1–3 the app still builds; CavaFace/Whisper/LLM fall back.

## Status (CP3)
Lifecycle (init / load / free) is wired against the real API surface; graph I/O binding (tensor names,
quant params) and the Whisper decode loop are `TODO` — confirm against the delivered binaries on-device
(cf. the CavaFace I/O caveat, §2.1). Whisper's full mel + decode is CP5.
