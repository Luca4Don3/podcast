#!/usr/bin/env bash
# Run from the checkout root after assembleDebug.
set -euo pipefail
shopt -s nullglob
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_SDK_ROOT or ANDROID_HOME}}"
bin="$sdk/build-tools/35.0.0"
packages=(android/legacy/build/outputs/apk/debug/*-debug.apk)
if [[ "${#packages[@]}" != 1 ]]; then printf 'Expected exactly one APK\n' >&2; exit 1; fi
apk="${packages[0]}"

metadata=$("$bin/aapt" dump badging "$apk")
printf '%s\n' "$metadata" | awk '/^package: |^sdkVersion: |^targetSdkVersion: |^launchable-activity: /'
[[ "$metadata" == *"package: name='com.podcastapp.legacy'"* ]]
[[ "$metadata" == *"sdkVersion:'14'"* ]]
[[ "$metadata" == *"targetSdkVersion:'34'"* ]]
[[ "$metadata" == *"launchable-activity: name='com.podcastapp.legacy.LegacyActivity'"* ]]
signature=$("$bin/apksigner" verify --min-sdk-version 14 --verbose "$apk")
printf '%s\n' "$signature"
[[ "$signature" == *'Verified using v1 scheme (JAR signing): true'* ]]
"$bin/zipalign" -c 4 "$apk"
python3 - "$apk" <<'PY'
import pathlib
import re
import sys
import zipfile

apk = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(apk) as package:
    dex = [name for name in package.namelist() if re.fullmatch(r'classes[0-9]*\.dex', name)]
    assert dex == ['classes.dex'], f'Expected single pre-Lollipop DEX, found {dex}'
    with package.open(dex[0]) as code:
        assert code.read(8) == b'dex\n035\0', 'Expected Dalvik DEX 035'
    assert not any(name.startswith('lib/') for name in package.namelist()), 'Legacy APK must be ABI-neutral'
    assert all(entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED) for entry in package.infolist()), 'Unexpected ZIP compression'
print(f'APK size: {apk.stat().st_size:,} bytes (single DEX, no native libraries)')
PY
mkdir -p output/legacy
cp -- "$apk" output/legacy/podcast-lite-api14-preview.apk
sha256sum -- output/legacy/podcast-lite-api14-preview.apk
