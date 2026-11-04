#!/usr/bin/env bash
# AEGIS//WARDRIVE — manual APK build (aapt2 + javac + d8 + apksigner)
set -euo pipefail

SDK="$HOME/android-sdk"
BT="$SDK/build-tools/35.0.0"
PLAT="$SDK/platforms/android-35/android.jar"
APP="/home/hermes/wardrive/app"
OUT="/home/hermes/wardrive/out"
LOG="/home/hermes/wardrive/build.log"

mkdir -p "$APP/assets"

step() { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$LOG"; }

: > "$LOG"
step "BUILD START"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

step "aapt2 compile resources"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip" 2>&1 | tee -a "$LOG"

step "aapt2 link manifest + resources"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -A "$APP/assets" \
  --min-sdk-version 29 --target-sdk-version 35 \
  --version-code 3 --version-name 1.2 \
  --auto-add-overlay \
  "$OUT/res.zip" 2>&1 | tee -a "$LOG"

step "javac sources"
find "$APP/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 17 -target 17 -encoding UTF-8 -nowarn \
  -classpath "$PLAT" -d "$OUT/obj" @"$OUT/sources.txt" 2>&1 | tee -a "$LOG"

step "d8 dex"
find "$OUT/obj" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$PLAT" --min-api 29 --release \
  --output "$OUT/dex" @"$OUT/classes.txt" 2>&1 | tee -a "$LOG"

step "package"
(cd "$OUT" && cp base.apk unsigned.apk && zip -q -j unsigned.apk dex/classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk" 2>&1 | tee -a "$LOG"

KS="$HOME/wardrive/debug.keystore"
if [ ! -f "$KS" ]; then
  step "generate keystore"
  keytool -genkeypair -keystore "$KS" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=AegisPilot, O=DedSec, C=US" 2>&1 | tee -a "$LOG"
fi

step "sign"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$OUT/AegisWardrive.apk" "$OUT/aligned.apk" 2>&1 | tee -a "$LOG"

step "verify"
"$BT/apksigner" verify --print-certs "$OUT/AegisWardrive.apk" 2>&1 | head -8 | tee -a "$LOG"

step "BUILD OK"
ls -lh "$OUT/AegisWardrive.apk" | tee -a "$LOG"
