# app/libs — optional ExecuTorch AAR override

ExecuTorch is pulled automatically from **Maven Central**
(`org.pytorch:executorch-android:1.3.1`, see `build.gradle.kts`), so **nothing is required here**.

Only use this folder to **pin/override** the ExecuTorch runtime — drop a specific
`executorch-android-*.aar` (from <https://github.com/pytorch/executorch/releases> or your own build)
and `build.gradle.kts` picks it up via `implementation(fileTree("libs") { include("*.aar") })`.

- Any `*.aar` here is **gitignored** (per-dev).
- `ai/ExecuTorchBackend.kt` loads the runtime **reflectively**, so the project compiles/runs (with
  graceful fallback) regardless — useful if the Maven artifact is ever unavailable or mismatched with
  the `.pte` export.
