# app/libs — ExecuTorch AAR (not committed)

Place `executorch-android-release.aar` here (from
<https://github.com/pytorch/executorch/releases>) to enable the face **detection** + **landmark**
`.pte` models (IMPLEMENTATION_PLAN.md §2, §16).

- The AAR is **gitignored** (`*.aar`) — each dev drops their own copy.
- `build.gradle.kts` picks it up via `implementation(fileTree("libs") { include("*.aar") })`.
- `ai/ExecuTorchBackend.kt` loads it **reflectively**, so the project still compiles and runs (with
  graceful fallback) when this folder is empty. No code change is needed once the AAR is added.
