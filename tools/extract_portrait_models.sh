#!/usr/bin/env bash
# Copies the three portrait models out of a Google Camera APK you already have into
# app/src/main/assets/gmodels/, where a local build picks them up.
#
# The models belong to Google. They are for your own phone only: the target folder is in
# .gitignore, so they are never committed or pushed.
#
# Usage: tools/extract_portrait_models.sh [path/to/GoogleCamera.apk]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
apk="${1:-$root/scp/MGC_9.7.047_V36_snap.apk}"
out="$root/app/src/main/assets/gmodels"

[ -f "$apk" ] || { echo "APK not found: $apk" >&2; exit 1; }
mkdir -p "$out"

extract() { # <name inside the APK> <name in our assets>
    unzip -p "$apk" "assets/$1.tflite.uncompressed" > "$out/$2.tflite"
    [ -s "$out/$2.tflite" ] || { echo "Missing in APK: $1" >&2; rm -f "$out/$2.tflite"; exit 1; }
    echo "  $2.tflite  ($(du -h "$out/$2.tflite" | cut -f1))"
}

echo "Extracting portrait models from $(basename "$apk"):"
extract raid_foreground_mosaic_512_qat subject_segmenter
extract portrait_matting_mask_1024_768 portrait_matting
extract midasnet_mobilenetv2_dptmqn_dec256_sep_082421_384_384_fp16_opt depth
echo "Done. Rebuild the app to include them."
