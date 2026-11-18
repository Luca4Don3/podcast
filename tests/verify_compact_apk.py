#!/usr/bin/env python3
"""Check the compact universal APK against a previously delivered debug APK."""

import argparse
from pathlib import Path
import re
import struct
import subprocess
from zipfile import ZipFile


ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
PAGE = 16384


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def run(tool, *args):
    return subprocess.check_output([str(tool), *(str(arg) for arg in args)], text=True)


def metadata(build_tools, apk):
    badging = run(build_tools / "aapt2", "dump", "badging", apk)
    fields = {}
    for key in ("name", "versionCode", "versionName", "minSdkVersion", "targetSdkVersion"):
        match = re.search(r"\b" + key + r"[:=]'([^']*)'", badging)
        require(match, f"{apk.name}: missing {key}")
        fields[key] = match[1]
    signature = run(build_tools / "apksigner", "verify", "--print-certs", apk)
    fields["signers"] = re.findall(r"^Signer #\d+ certificate SHA-256 digest: (\w+)$", signature, re.M)
    require(fields["signers"], f"{apk.name}: no signing certificate")
    return fields


def verify_native_libs(apk):
    abis = set()
    libraries = 0
    with ZipFile(apk) as zf:
        bad = zf.testzip()
        require(bad is None, f"Corrupt archive entry: {bad}")
        for name in zf.namelist():
            if not (name.startswith("lib/") and name.endswith(".so")):
                continue
            _, abi, _ = name.split("/", maxsplit=2)
            abis.add(abi)
            libraries += 1
            data = zf.read(name)
            require(data[:4] == b"\x7fELF" and data[4] in (1, 2) and data[5] == 1, f"Unsupported ELF: {name}")
            if data[4] == 1:
                phoff = struct.unpack_from("<I", data, 28)[0]
                entsize, count = struct.unpack_from("<HH", data, 42)
                layout = (4, 8, 28, "<I")
            else:
                phoff = struct.unpack_from("<Q", data, 32)[0]
                entsize, count = struct.unpack_from("<HH", data, 54)
                layout = (8, 16, 48, "<Q")
            loaded = 0
            for i in range(count):
                base = phoff + i * entsize
                if struct.unpack_from("<I", data, base)[0] != 1:  # PT_LOAD
                    continue
                loaded += 1
                foff, vaddr, align = (struct.unpack_from(layout[3], data, base + off)[0] for off in layout[:3])
                require(align >= PAGE and foff % PAGE == vaddr % PAGE, f"16 KB ELF alignment failed: {name}")
            require(loaded, f"No loadable segments: {name}")
    require(abis == ABIS, f"Expected all four ABIs, got {sorted(abis)}")
    return libraries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("compact", type=Path)
    args = parser.parse_args()
    old, new = (metadata(args.build_tools, apk) for apk in (args.baseline, args.compact))
    require(new["name"] == old["name"] == "com.podcastapp.debug", "App ID changed")
    require(new["signers"] == old["signers"], "Signer changed; in-place upgrade will not work")
    require(int(new["versionCode"]) > int(old["versionCode"]), "Version code did not increase")
    require(new["minSdkVersion"] == old["minSdkVersion"] == "24", "Minimum Android version changed")
    require(new["targetSdkVersion"] == old["targetSdkVersion"], "Target SDK changed")
    libraries = verify_native_libs(args.compact)
    run(args.build_tools / "zipalign", "-c", "-P", "16", "4", args.compact)
    old_size, new_size = (apk.stat().st_size for apk in (args.baseline, args.compact))
    require(new_size < old_size, "Compact APK is not smaller")
    print(f"{old_size:,} -> {new_size:,} bytes ({100 * (1 - new_size / old_size):.1f}% smaller)")
    print(f"{new['versionName']}; API {new['minSdkVersion']}+; target API {new['targetSdkVersion']}")
    print(f"Same app ID and signing certificate; {libraries} native libraries across {', '.join(sorted(ABIS))}")
    print("APK and ELF 16 KB alignment passed (static checks, not on-device tests)")


if __name__ == "__main__":
    main()
