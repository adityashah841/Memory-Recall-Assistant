#!/usr/bin/env bash
# Push on-device model weights laptop → S25 (IMPLEMENTATION_PLAN.md §16, option 2).
#
# Models are NOT bundled in the APK / committed to git. This copies them to the app's external files
# dir, where ai/ModelAssets.kt loads them by path. Re-run only when a model file changes.
#
# Usage:   scripts/push_models.sh [SOURCE_DIR]
#   SOURCE_DIR defaults to ~/Downloads (where the delivered files currently live).
#
# Expects in SOURCE_DIR (names as delivered):
#   mediapipe_face_detection.pte
#   mediapipe_face_landmark_detector.pte
#   cavaface.bin
#   whisper_small_quantized-*/{encoder.bin,decoder.bin,metadata.json}
#   llama_v3_2_1b_instruct-genie-*/{part{1,2,3}_of_3.bin,genie_config.json,htp_backend_ext_config.json,
#                                   tokenizer.json,tokenizer_config.json,special_tokens_map.json,config.json}
set -euo pipefail

SRC="${1:-$HOME/Downloads}"
PKG="com.synapseriders.memory_recall_app"
DEST="/sdcard/Android/data/${PKG}/files/models"

command -v adb >/dev/null || { echo "ERROR: adb not found (install platform-tools)"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "ERROR: no device. Connect the S25 with USB debugging on."; exit 1; }

echo "Source : $SRC"
echo "Device : $(adb get-serialno)"
echo "Dest   : $DEST"
adb shell mkdir -p "$DEST/whisper" "$DEST/llama_genie"

push() {  # push <local> <remote-name>
  if [[ -f "$1" ]]; then
    echo "  → $2"
    adb push "$1" "$DEST/$2" >/dev/null
  else
    echo "  ! missing: $1 (skipped)"
  fi
}

echo "[1/4] ExecuTorch face models"
push "$SRC/mediapipe_face_detection.pte"         "mediapipe_face_detection.pte"
push "$SRC/mediapipe_face_landmark_detector.pte" "mediapipe_face_landmark_detector.pte"

echo "[2/4] CavaFace"
push "$SRC/cavaface.bin" "cavaface.bin"

echo "[3/4] Whisper"
WHISPER_DIR="$(find "$SRC" -maxdepth 1 -type d -name 'whisper_small_quantized-*' | head -1 || true)"
if [[ -n "$WHISPER_DIR" ]]; then
  push "$WHISPER_DIR/encoder.bin"  "whisper/encoder.bin"
  push "$WHISPER_DIR/decoder.bin"  "whisper/decoder.bin"
  push "$WHISPER_DIR/metadata.json" "whisper/metadata.json"
else
  echo "  ! no whisper_small_quantized-* dir under $SRC"
fi

echo "[4/4] Llama Genie bundle"
GENIE_DIR="$(find "$SRC" -maxdepth 2 -type d -name 'llama_v3_2_1b_instruct-genie-*' | head -1 || true)"
if [[ -n "$GENIE_DIR" ]]; then
  for f in part1_of_3.bin part2_of_3.bin part3_of_3.bin genie_config.json \
           htp_backend_ext_config.json tokenizer.json tokenizer_config.json \
           special_tokens_map.json config.json; do
    push "$GENIE_DIR/$f" "llama_genie/$f"
  done
else
  echo "  ! no llama_v3_2_1b_instruct-genie-* dir under $SRC (unzip the delivered .zip first)"
fi

echo "Done. On-device tree:"
adb shell ls -R "$DEST"
