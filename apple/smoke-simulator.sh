#!/usr/bin/env bash
# Smoke-test install and launch without reusing an existing simulator.
set -euo pipefail

if [[ $# -ne 1 || ! -d "$1" ]]; then
    echo 'Usage: bash apple/smoke-simulator.sh APP_PATH' >&2
    exit 2
fi
app="$(CDPATH= cd -- "$1" && pwd -P)"
# Resolve screen sizes without booting candidates or using existing UDIDs.
selection=$(python3 - "$app/Info.plist" <<'PY'
import json
from pathlib import Path
import plistlib
import re
import subprocess
import sys

try:
    with open(sys.argv[1], 'rb') as fp:
        info = plistlib.load(fp)
except (OSError, ValueError) as exc:
    sys.exit(f'Cannot read app Info.plist: {exc}')

bundle_id = info.get('CFBundleIdentifier') if isinstance(info, dict) else None
if bundle_id != 'com.podcastapp.apple':
    sys.exit(f'Expected CFBundleIdentifier com.podcastapp.apple, got {bundle_id!r}')
minimum = info.get('MinimumOSVersion', '0')
if not isinstance(minimum, str) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+){0,2}', minimum):
    sys.exit(f'Invalid MinimumOSVersion in app Info.plist: {minimum!r}')
minimum = tuple(map(int, minimum.split('.'))) + (0,) * (2 - minimum.count('.'))

try:
    inventory = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', '-j']))
except (OSError, ValueError, subprocess.CalledProcessError) as exc:
    sys.exit(f'Cannot list installed simulators: {exc}')

types = {device['identifier']: device for device in inventory['devicetypes']}
runtimes = []
for runtime in inventory['runtimes']:
    identifier = runtime['identifier']
    match = re.fullmatch(r'com\.apple\.CoreSimulator\.SimRuntime\.iOS-(\d+)-(\d+)(?:-(\d+))?', identifier)
    if match and runtime.get('isAvailable', not runtime.get('availabilityError')):
        version = tuple(int(part or 0) for part in match.groups())
        if version >= minimum:
            runtimes.append((version, identifier, runtime))


def compatible_types(version, identifier, runtime):
    supported = runtime.get('supportedDeviceTypes')
    if supported is not None:
        return {device['identifier'] for device in supported if device.get('isAvailable', True)}
    # Older simctl JSON can omit the runtime's type list. Use the installed
    # type's version range, or a matching available device if it is missing.
    major, minor, patch = version
    packed = (major << 16) | (minor << 8) | patch
    existing = {device.get('deviceTypeIdentifier') for device in inventory.get('devices', {}).get(identifier, [])
                if device.get('isAvailable', not device.get('availabilityError'))}
    compatible = set()
    for type_id, device in types.items():
        lower, upper = device.get('minRuntimeVersion'), device.get('maxRuntimeVersion')
        if (lower is not None and packed < lower) or (upper is not None and packed > upper):
            continue
        if (lower is not None and upper is not None) or type_id in existing:
            compatible.add(type_id)
    return compatible


def screen_points(device):
    try:
        profile = Path(device['bundlePath']) / 'Contents/Resources/profile.plist'
        with profile.open('rb') as fp:
            info = plistlib.load(fp)
        scale = info['mainScreenScale']
        width, height = info['mainScreenWidth'], info['mainScreenHeight']
        if scale in (1, 2, 3) and width > 0 and height > 0:
            return tuple(sorted((width / scale, height / scale)))
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return None


# Portrait short-edge points, not UIKit size classes. Leave a gap between
# buckets so near-identical widths aren't reported as size coverage.
limits = {'iPhone': (402, 414), 'iPad': (834, 1024)}
# Well-known model groups for when a type's screen profile is missing.
models = {
    'iPhone-compact': (r'iPhone-(?:SE(?:-.*)?|1[23]-mini)', r'iPhone-1[2-7](?:e|-Pro)?'),
    'iPhone-large': (r'iPhone-\d+-Pro-Max', r'iPhone-\d+-Plus', r'iPhone-Air'),
    'iPad-compact': (r'iPad-mini(?:-.*)?', r'iPad-(?:Air|Pro)-+11-inch(?:-.*)?',
                     r'iPad-(?:A16|-*10th-generation-*)'),
    'iPad-large': (r'iPad-(?:Air|Pro)-+13-inch(?:-.*)?', r'iPad-Pro-+12-9-inch(?:-.*)?'),
}


def classify(model, family, points):
    if points is not None:
        short, long = points
        compact, large = limits[family]
        evidence = f'{short:g}x{long:g} pt from device profile'
        if short <= compact:
            return f'{family}-compact', (0, short, long), evidence
        if short >= large:
            return f'{family}-large', (0, -short, -long), evidence
        return None
    for size in ('compact', 'large'):
        slot = f'{family}-{size}'
        for priority, pattern in enumerate(models[slot]):
            if re.fullmatch(pattern, model):
                return slot, (1, priority), 'known model fallback; screen profile unavailable'
    return None


# Cache profile reads across runtimes, but consider compatibility per runtime.
screens = {}
for version, runtime_id, runtime in sorted(runtimes, key=lambda item: item[:2], reverse=True):
    slots = {}
    for type_id in sorted(compatible_types(version, runtime_id, runtime)):
        match = re.fullmatch(r'com\.apple\.CoreSimulator\.SimDeviceType\.((iPhone|iPad)(?:-[A-Za-z0-9-]+)?)', type_id)
        if not match:
            continue
        if type_id not in screens:
            screens[type_id] = screen_points(types.get(type_id, {}))
        result = classify(match[1], match[2], screens[type_id])
        if result is not None:
            slot, rank, evidence = result
            candidate = (rank, type_id, evidence)
            if slot not in slots or candidate < slots[slot]:
                slots[slot] = candidate
    missing = [slot for slot in models if slot not in slots]
    if missing:
        print(f'Skipping {runtime_id}; no representative type for: {", ".join(missing)}', file=sys.stderr)
        continue
    print(bundle_id, runtime_id)
    for slot in models:
        _, type_id, evidence = slots[slot]
        print(slot, type_id, evidence, sep='\t')
    break
else:
    sys.exit('No available iOS runtime compatible with the app has four distinct representative screen types.')
PY
)

active_udid=''
cleanup() {
    local status=$?
    if [[ -n "$active_udid" ]]; then
        xcrun simctl shutdown "$active_udid" || printf 'Warning: could not shut down %s\n' "$active_udid" >&2
    fi
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
    local slot=$1 udid=$2 category actual
    printf 'Booting %s smoke simulator (%s)\n' "$slot" "$udid"
    xcrun simctl boot "$udid"
    xcrun simctl bootstatus "$udid" -b
    xcrun simctl install "$udid" "$app"
    for category in large accessibility-extra-extra-extra-large; do
        xcrun simctl ui "$udid" content_size "$category"
        actual=$(xcrun simctl ui "$udid" content_size)
        if [[ "$actual" != "$category" ]]; then
            printf '%s: expected Dynamic Type %s, got %s\n' "$slot" "$category" "$actual" >&2
            return 1
        fi
        printf 'Launching %s with Dynamic Type %s (verified simulator setting)\n' "$slot" "$actual"
        xcrun simctl launch --terminate-running-process "$udid" "$bundle_id"
    done
}

# Keep one new simulator booted at a time. Leave existing devices untouched.
name="podcast-smoke-$$-$RANDOM"
{
    read -r bundle_id runtime <&3
    printf 'Selected runtime: %s\n' "$runtime"
    while IFS=$'\t' read -r slot type_id evidence <&3; do
        printf 'Selected %s: %s (%s)\n' "$slot" "$type_id" "$evidence"
        active_udid=$(create_device "$name-$slot" "$type_id")
        smoke_device "$slot" "$active_udid"
        xcrun simctl shutdown "$active_udid"
        active_udid=''
    done
} 3<<< "$selection"
echo 'Install and launch passed on four simulators at standard and maximum Dynamic Type; layout was not verified.'
