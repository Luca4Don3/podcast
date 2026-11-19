#!/usr/bin/env bash
# Produce a simulator-only artifact; never package this as a device IPA.
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
version=$(python3 - "$root/android/app/build.gradle.kts" <<'PY'
from pathlib import Path
import re, sys
src = Path(sys.argv[1]).read_text()
version, = re.findall(r'^val podcastVersion = "([0-9]+[.][0-9]+(?:[.][0-9]+|-rc[1-9][0-9]*)?)"$', src, re.M)
build, = re.findall(r'^val podcastVersionCode = ([1-9][0-9]*)$', src, re.M)
print(version, build)
PY
)
read -r version build <<< "$version"
marketing="${version%%-rc*}"
if [[ "${GITHUB_REF_TYPE:-}" == tag && "${GITHUB_REF_NAME:-}" != "v$version" ]]; then
    echo 'Release tag does not match the app version.' >&2
    exit 1
fi
work="${1:?Usage: bash apple/build-simulator.sh output-directory}"
mkdir -p -- "$work"
work="$(cd -- "$work" && pwd)"
xcodebuild -project "$root/apple/podcast.xcodeproj" -scheme podcast \
    -configuration Release -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$work/DerivedData" CODE_SIGNING_ALLOWED=NO ONLY_ACTIVE_ARCH=NO \
    MARKETING_VERSION="$marketing" CURRENT_PROJECT_VERSION="$build" build
app="$work/DerivedData/Build/Products/Release-iphonesimulator/podcast.app"
python3 - "$app/Info.plist" "$marketing" "$build" <<'PY'
import plistlib, sys
with open(sys.argv[1], 'rb') as fp:
    info = plistlib.load(fp)
assert sorted(info['UIDeviceFamily']) == [1, 2], 'iPhone and iPad must both be supported'
assert info['CFBundleSupportedPlatforms'] == ['iPhoneSimulator']
assert info['CFBundleShortVersionString'] == sys.argv[2]
assert info['CFBundleVersion'] == sys.argv[3]
assert not info.get('UIRequiresFullScreen', False)
assert set(info['UISupportedInterfaceOrientations~ipad']) == {
    'UIInterfaceOrientationPortrait', 'UIInterfaceOrientationPortraitUpsideDown',
    'UIInterfaceOrientationLandscapeLeft', 'UIInterfaceOrientationLandscapeRight',
}
print('Verified universal Apple simulator metadata and iPad orientations')
PY
/usr/bin/ditto -c -k --keepParent "$app" "$work/podcast-$version-ios-simulator.zip"
