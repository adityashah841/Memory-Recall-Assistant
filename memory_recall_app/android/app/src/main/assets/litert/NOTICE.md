# facenet_512.tflite

Face identity embedder used by the non-Qualcomm backend (`ai/GoogleModelRunner.kt` →
`ai/FaceNetEmbedder.kt`).

- **Model:** FaceNet (Inception-ResNet-v1), 512-d embedding.
- **I/O:** input `float32[1, 160, 160, 3]` (RGB, NHWC, per-image standardized), output
  `float32[1, 512]` (L2-normalized in app code).
- **SHA-256:** `4ff97d406893bc4aae2d922c28287045d58fa9fbcbfeb731b975fd2debbf1a80`
- **Source:** copied unchanged from
  [shubham0204/FaceRecognition_With_FaceNet_Android](https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android)
  (`app/src/main/assets/facenet_512.tflite`), licensed under the Apache License 2.0. That project
  states its FaceNet weights come from
  [nyoki-mtl/keras-facenet](https://github.com/nyoki-mtl/keras-facenet).

This file is committed on purpose (≈24 MB). The large Qualcomm weights (`assets/models/`, `*.bin`)
remain git-ignored and are pushed to the device with `scripts/push_models.sh`.
