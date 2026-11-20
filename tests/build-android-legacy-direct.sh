#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob globstar
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_SDK_ROOT or ANDROID_HOME}}"
export PATH="$JAVA_HOME/bin:$PATH"
command -v javac >/dev/null || { echo "JDK is required" >&2; exit 1; }
bin="$sdk/build-tools/35.0.0"
platform="$sdk/platforms/android-35/android.jar"
api14="$sdk/platforms/android-14/android.jar"
test -f "$api14" || { echo "Install platforms;android-14" >&2; exit 1; }
src=android/legacy/src/main
[[ -f "$src/java/com/podcastapp/legacy/LegacyActivity.java" ]]
work=$(mktemp -d "${TMPDIR:-/tmp}/legacy-apk.XXXXXX")
printf 'APK scratch: %s\n' "$work"
mkdir "$work/classes" "$work/dex" "$work/java"
python3 - "$src/AndroidManifest.xml" "$work/AndroidManifest.xml" <<'PY'
import sys
from pathlib import Path
text=Path(sys.argv[1]).read_text(encoding='utf-8')
assert text.count('<manifest xmlns:android=') == 1
text=text.replace('<manifest xmlns:android=', '<manifest package="com.podcastapp.legacy" xmlns:android=',1)
Path(sys.argv[2]).write_text(text, encoding='utf-8')
PY
compiled=()
if [[ -d "$src/res" ]]; then
  "$bin/aapt2" compile --dir "$src/res" -o "$work/resources.zip"
  compiled=(-R "$work/resources.zip")
fi
"$bin/aapt2" link -I "$platform" --manifest "$work/AndroidManifest.xml" \
  --auto-add-overlay --min-sdk-version 14 --target-sdk-version 34 --version-code 1 --version-name '0.1.0-preview' \
  --debug-mode --java "$work/java" -o "$work/unsigned.apk" "${compiled[@]}"
javac -source 8 -target 8 -encoding UTF-8 -bootclasspath "$api14" -d "$work/classes" \
  "$src"/java/**/*.java "$work"/java/**/*.java
classFiles=("$work"/classes/**/*.class)
"$bin/d8" --min-api 14 --lib "$api14" --output "$work/dex" "${classFiles[@]}"
python3 - "$work/unsigned.apk" "$work/dex/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', compression=zipfile.ZIP_DEFLATED) as package:
    package.write(sys.argv[2], 'classes.dex')
PY
"$bin/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/test.jks" -storepass android -keypass android \
  -alias test -keyalg RSA -keysize 2048 -validity 365 -dname 'CN=Podcast Test, O=Debug, C=US' >/dev/null
"$bin/apksigner" sign --ks "$work/test.jks" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 14 --v1-signing-enabled true --v2-signing-enabled true --out "$work/podcast-lite-api14-device-test.apk" "$work/aligned.apk"
"$bin/apksigner" verify --verbose --min-sdk-version 14 "$work/podcast-lite-api14-device-test.apk"
mkdir -p output/legacy
cp -- "$work/podcast-lite-api14-device-test.apk" output/legacy/podcast-lite-api14-preview.apk
"$bin/aapt" dump badging "$work/podcast-lite-api14-device-test.apk" | awk '/^package: |^sdkVersion: |^targetSdkVersion: |^launchable-activity: /'
ls -lh -- "$work/podcast-lite-api14-device-test.apk"
sha256sum -- "$work/podcast-lite-api14-device-test.apk"
