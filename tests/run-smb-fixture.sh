#!/usr/bin/env bash
# Start an isolated, authenticated Samba fixture using synthetic data only.
set -euo pipefail
if [[ "$#" -lt 2 || "$1" != "--" ]]; then
    echo "Usage: bash tests/run-smb-fixture.sh -- command [args...]" >&2
    exit 2
fi
shift
root="$(cd -- "$(dirname -- "$0")" && pwd)"
image="$(docker build -q "$root/smb-fixture")"
[[ "$image" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo 'Unexpected fixture image ID' >&2; exit 1; }
export PODCAST_TEST_SMB_USER=podcast_fixture
export PODCAST_TEST_SMB_PASSWORD='fixture-only-0123456789'
export PODCAST_TEST_SMB_DOMAIN=WORKGROUP
cid="$(docker run --rm -d -p 127.0.0.1::445 \
    -e PODCAST_TEST_SMB_PASSWORD "$image")"
[[ "$cid" =~ ^[a-f0-9]{64}$ ]] || { echo 'Unexpected fixture container ID' >&2; exit 1; }
trap 'docker stop -t 4 "$cid" >/dev/null 2>&1 || true' EXIT
port="$(docker inspect -f '{{(index (index .NetworkSettings.Ports "445/tcp") 0).HostPort}}' "$cid")"
[[ "$port" =~ ^[0-9]+$ ]] || { echo 'Missing Samba fixture port' >&2; exit 1; }
export PODCAST_TEST_SMB_URL="smb://127.0.0.1:$port/podcasts"
if ! python3 - "$port" <<'PY'
import socket, sys, time
port = int(sys.argv[1]); deadline = time.monotonic() + 20
while time.monotonic() < deadline:
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.5):
            sys.exit(0)
    except OSError:
        time.sleep(0.2)
raise SystemExit('Samba fixture did not start')
PY
then
    docker logs "$cid" >&2 || true
    exit 1
fi
"$@"
