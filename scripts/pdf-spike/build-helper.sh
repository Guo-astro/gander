#!/bin/sh
# Builds the isolated renderer spike's helper app, another app whose provider hands Gander a
# PDF out of its own private storage. No Gradle: javac, d8, aapt2 and the debug key.
#
#   scripts/pdf-spike/build-helper.sh <a.pdf> <empty out dir>
set -eu
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-36/android.jar"
JAVAC="${JAVAC:-/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PDF="$1"
OUT="$2"
mkdir -p "$OUT/classes" "$OUT/assets" "$OUT/dex"
cp "$PDF" "$OUT/assets/spike.pdf"
"$JAVAC" --release 8 -nowarn -cp "$AJ" -d "$OUT/classes" "$HERE"/helper/src/com/arjun/gander/spikehelper/*.java
"$BT/d8" --min-api 26 --lib "$AJ" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
"$BT/aapt2" link -I "$AJ" --manifest "$HERE/helper/AndroidManifest.xml" -A "$OUT/assets" \
    --min-sdk-version 26 --target-sdk-version 34 -o "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey --out "$OUT/spike-helper.apk" "$OUT/aligned.apk"
echo "$OUT/spike-helper.apk"
