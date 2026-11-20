#!/usr/bin/env bash
# GitHub-hosted CI only. Creates a disposable, signed Debian root filesystem.
set -euo pipefail
: "${RUNNER_TEMP:?}" "${ABI:?}" "${ELF_CLASS:?}" "${ELF_MACHINE:?}"
case "$ABI:${QEMU:-}" in
  amd64:|i386:|arm64:|armhf:arm|mipsel:mipsel|mips64el:mips64el) ;;
  *) printf '%s\n' 'Unsupported Debian CI architecture'; exit 2 ;;
esac

packages=(debootstrap debian-archive-keyring)
if [[ -n "${QEMU:-}" ]]; then packages+=(qemu-user-static binfmt-support); fi
sudo apt-get update -q
sudo apt-get install -yq --no-install-recommends "${packages[@]}"
if [[ -n "${QEMU:-}" ]]; then
  sudo update-binfmts --enable "qemu-$QEMU"
fi

scratch=$(mktemp -d "$RUNNER_TEMP/podcast-$ABI.XXXXXX")
root="$scratch/root"
export DEBIAN_FRONTEND=noninteractive
sudo debootstrap --arch="$ABI" --foreign --variant=minbase --force-check-gpg \
  --keyring=/usr/share/keyrings/debian-archive-keyring.gpg bookworm "$root" http://deb.debian.org/debian
if [[ -n "${QEMU:-}" ]]; then
  sudo install -m 0755 "/usr/bin/qemu-$QEMU-static" "$root/usr/bin/"
fi
sudo chroot "$root" /debootstrap/debootstrap --second-stage
cleanup() {
  if mountpoint -q "$root/proc"; then sudo umount -- "$root/proc"; fi
}
trap cleanup EXIT
sudo mount -t proc proc "$root/proc"
sudo chroot "$root" env DEBIAN_FRONTEND=noninteractive apt-get update -q
sudo chroot "$root" env DEBIAN_FRONTEND=noninteractive apt-get install -yq --no-install-recommends \
  build-essential cmake pkg-config libgtk-3-dev libgstreamer1.0-dev libcurl4-openssl-dev libxml2-dev \
  gstreamer1.0-plugins-base gstreamer1.0-plugins-good glib-networking ca-certificates xvfb xauth

# The app and tests run without root. Do not copy repository metadata or credentials.
sudo install -d -o 1000 -g 1000 "$root/work" "$root/work/home"
sudo install -d "$root/work/source"
sudo cp -a linux "$root/work/source/"
sudo chroot --userspec=1000:1000 "$root" env HOME=/work/home LC_ALL=C.UTF-8 \
  bash -eu -c '
    test "$(dpkg --print-architecture)" = "$1"
    cmake -S /work/source/linux -B /work/build -DCMAKE_BUILD_TYPE=Release
    cmake --build /work/build --parallel 2
    ctest --test-dir /work/build --output-on-failure --no-tests=error
    cmake --install /work/build --prefix /work/stage
  ' bash "$ABI"

exe="$root/work/stage/bin/podcast-linux"
readelf -h "$exe"
python3 - "$exe" "$ELF_CLASS" "$ELF_MACHINE" "$ABI" <<'PY'
import struct
import sys
with open(sys.argv[1], 'rb') as f:
    data = f.read(64)
klass, machine = int(sys.argv[2]), int(sys.argv[3])
assert data[:7] == b'\x7fELF' + bytes((klass, 1, 1)), 'wrong class or endianness'
assert struct.unpack_from('<H', data, 18)[0] == machine, 'wrong ELF machine'
if sys.argv[4] == 'armhf':
    assert struct.unpack_from('<I', data, 36)[0] & 0x400, 'not ARM hard-float'
if sys.argv[4] == 'mipsel':
    flags = struct.unpack_from('<I', data, 36)[0]
    assert flags & 0xF000 == 0x1000 and not flags & 0x20, 'not MIPS o32'
if sys.argv[4] == 'mips64el':
    flags = struct.unpack_from('<I', data, 48)[0]
    assert not flags & 0xF020, 'not MIPS n64'
PY
mkdir -p output/desktop
tar --owner=0 --group=0 -C "$root/work/stage" -czf "output/desktop/podcast-linux-debian12-$ABI.tar.gz" .
