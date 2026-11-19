#!/usr/bin/env bash
# Smoke-test install and launch without reusing an existing simulator.
set -euo pipefail

if [[ $# -ne 1 || ! -d "$1" ]]; then
    echo 'Usage: bash apple/smoke-simulator.sh APP_PATH' >&2
    exit 2
fi
app="$(CDPATH= cd -- "$1" && pwd -P)"
bundle_id=$(python3 - "$app/Info.plist" <<'PY'
import plistlib
import sys

try:
    with open(sys.argv[1], 'rb') as fp:
        info = plistlib.load(fp)
except (OSError, ValueError) as exc:
    sys.exit(f'Cannot read app Info.plist: {exc}')

bundle_id = info.get('CFBundleIdentifier') if isinstance(info, dict) else None
if bundle_id != 'com.podcastapp.apple':
    sys.exit(f'Expected CFBundleIdentifier com.podcastapp.apple, got {bundle_id!r}')
print(bundle_id)
PY
)

# Reuse only the device type identifiers, never UDIDs from the listing.
selection=$(xcrun simctl list devices available -j | python3 -c '
import json
import re
import sys

pairs = []
for runtime, devices in json.load(sys.stdin)["devices"].items():
    match = re.fullmatch(r"com\.apple\.CoreSimulator\.SimRuntime\.iOS-([0-9]+(?:-[0-9]+)*)", runtime)
    if not match:
        continue
    types = {}
    for device in devices:
        if not device.get("isAvailable", True):
            continue
        identifier = device.get("deviceTypeIdentifier", "")
        family = re.fullmatch(r"com\.apple\.CoreSimulator\.SimDeviceType\.(iPhone|iPad)(?:-[A-Za-z0-9-]+)?", identifier)
        if family:
            types.setdefault(family[1], identifier)
    if "iPhone" in types and "iPad" in types:
        version = tuple(map(int, match[1].split("-")))
        pairs.append((version, runtime, types["iPhone"], types["iPad"]))
if not pairs:
    sys.exit("No available iOS simulator runtime with both an iPhone and an iPad.")
_, runtime, iphone, ipad = max(pairs)
print(runtime, iphone, ipad)
')
read -r runtime iphone_type ipad_type <<< "$selection"
printf 'Selected %s: %s and %s\n' "$runtime" "$iphone_type" "$ipad_type"

iphone_udid=''
ipad_udid=''
cleanup() {
    local status=$? udid
    for udid in "$iphone_udid" "$ipad_udid"; do
        [[ -n "$udid" ]] || continue
        xcrun simctl shutdown "$udid" || printf 'Warning: could not shut down %s\n' "$udid" >&2
    done
    exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

create_device() {
    local udid
    udid=$(xcrun simctl create "$1" "$2" "$runtime") || return
    if [[ ! "$udid" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]]; then
        echo 'simctl create did not return a device UUID.' >&2
        return 1
    fi
    printf '%s\n' "$udid"
}

smoke_device() {
    local family=$1 udid=$2
    printf 'Booting %s smoke simulator (%s)\n' "$family" "$udid"
    xcrun simctl boot "$udid"
    xcrun simctl bootstatus "$udid" -b
    xcrun simctl install "$udid" "$app"
    xcrun simctl launch "$udid" "$bundle_id"
}

name="podcast-smoke-$$-$RANDOM"
iphone_udid=$(create_device "$name-iPhone" "$iphone_type")
smoke_device iPhone "$iphone_udid"
ipad_udid=$(create_device "$name-iPad" "$ipad_type")
smoke_device iPad "$ipad_udid"
echo 'Install and launch passed on iPhone and iPad.'
