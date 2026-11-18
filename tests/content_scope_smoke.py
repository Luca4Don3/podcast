#!/usr/bin/env python3
"""Black-box regression for both backends. Uses only loopback test sources.

Usage: python3 tests/content_scope_smoke.py /absolute/path/to/podcast-server [...]
Test databases/logs are kept under .temp for review. No existing service is stopped.
"""
import argparse
import contextlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class SourceHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        base = f"http://127.0.0.1:{self.server.server_port}"
        path = self.path.split("?", 1)[0]
        if path == "/rss":
            content_type = "application/rss+xml"
            data = f'''<rss version="2.0"><channel><title>Owned test feed</title>
<link>{base}</link><description>Local fixture, not bundled content</description>
<item><guid>test-episode</guid><title>Test episode</title>
<enclosure url="{base}/audio.mp3" type="audio/mpeg" length="32" />
</item></channel></rss>'''.encode()
        elif path == "/stations":
            content_type = "application/json"
            data = json.dumps([{
                "stationuuid": "local-test", "name": "Test directory station",
                "url_resolved": base + "/audio.mp3", "codec": "MP3", "hls": 0,
                "bitrate": 128, "country": "China", "tags": "test",
            }]).encode()
        elif path == "/podcasts":
            content_type = "application/json"
            data = json.dumps({"resultCount": 1, "results": [{
                "collectionName": "Owned test feed", "artistName": "Fixture",
                "feedUrl": base + "/rss", "collectionId": 1,
            }]}).encode()
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def request(base, method, path, token="", body=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    raw = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=raw, headers=headers, method=method)
    try:
        response = OPENER.open(req, timeout=6)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        text = response.read().decode()
    assert status == expected, f"{method} {path}: expected {expected}, got {status}"
    return json.loads(text) if text else {}


@contextlib.contextmanager
def server(binary, directory_mode, upstream):
    folder = Path(tempfile.mkdtemp(prefix="content-scope-", dir=ROOT / ".temp"))
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    env = dict(os.environ)
    env.update({
        "APP_ADDR": f"127.0.0.1:{port}", "APP_ENV": "development",
        "APP_DATA_DIR": str(folder), "DATABASE_PATH": str(folder / "podcast.db"),
        "DATABASE_DSN": "file:" + str(folder / "podcast.db"),
        "JWT_SECRET": secrets.token_hex(32), "SIGNUP_INVITE_CODE": "",
        "WORKER_THREADS": "4", "RADIO_BROWSER_URL": "", "ITUNES_SEARCH_URL": "",
    })
    if directory_mode == "unset":
        del env["RADIO_BROWSER_URL"]
        del env["ITUNES_SEARCH_URL"]
    elif directory_mode == "whitespace":
        env["RADIO_BROWSER_URL"] = "  \t "
        env["ITUNES_SEARCH_URL"] = "  \t "
    elif directory_mode == "configured":
        env["RADIO_BROWSER_URL"] = upstream + "/stations"
        env["ITUNES_SEARCH_URL"] = upstream + "/podcasts"
    with (folder / "server.log").open("w") as log:
        proc = subprocess.Popen([str(binary), "-env-file", str(folder / "no-env")],
                                cwd=folder, env=env, stdout=log, stderr=log)
        base = f"http://127.0.0.1:{port}"
        try:
            deadline = time.monotonic() + 10
            while True:
                assert proc.poll() is None, f"test process exited; see {folder.name}/server.log"
                try:
                    request(base, "GET", "/healthz")
                    break
                except (urllib.error.URLError, TimeoutError, ConnectionError):
                    if time.monotonic() >= deadline:
                        raise AssertionError("test server readiness timed out")
                    threading.Event().wait(0.05)
            yield base
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=4)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=4)
                raise AssertionError("test server did not shut down gracefully")
    assert proc.returncode == 0, f"test process exit: {proc.returncode}"


def register(base, email):
    result = request(base, "POST", "/api/v1/auth/register", body={
        "email": email, "password": secrets.token_urlsafe(18), "displayName": "Test",
    }, expected=201)
    return result["token"]["accessToken"]


def exercise(binary, mode, upstream):
    with server(binary, mode, upstream) as base:
        owner = register(base, "owner@example.test")
        for path in ("/api/v1/discover/stations?q=test", "/api/v1/discover/search?q=test"):
            request(base, "GET", path, expected=401)
            result = request(base, "GET", path, owner)
            if mode == "configured":
                assert len(result["items"]) == 1, f"explicit directory did not work: {path}"
            else:
                assert result["items"] == [] and result["total"] == 0
                assert result["enabled"] is False, f"directory not disabled: {path}"
        if mode == "empty":
            other = register(base, "other@example.test")
            assert request(base, "GET", "/api/v1/stations", owner)["items"] == []
            station = request(base, "POST", "/api/v1/stations", owner, {
                "name": "Personal station", "streamUrl": upstream + "/audio.mp3",
            })["station"]
            sid = station["id"]
            request(base, "PUT", f"/api/v1/stations/{sid}/favorite", owner, {"favorite": True})
            request(base, "PUT", f"/api/v1/stations/{sid}/favorite", other, {"favorite": True}, expected=404)
            request(base, "DELETE", f"/api/v1/stations/{sid}", other, expected=404)
            assert request(base, "GET", "/api/v1/stations", other)["items"] == []
            request(base, "POST", "/api/v1/stations", owner, {"streamUrl": "file:///test"}, expected=400)
            result = request(base, "POST", "/api/v1/stations/import", owner, {"stations": [
                {"name": "Personal MP3", "streamUrl": upstream + "/second.mp3"},
                {"name": "Personal HLS", "streamUrl": upstream + "/third.m3u8"},
            ]})
            assert result["imported"] == 2
            stations = request(base, "GET", "/api/v1/stations", owner)["items"]
            assert len(stations) == 3 and all(s["source"] == "manual" for s in stations)
            assert next(s for s in stations if s["name"] == "Personal HLS")["isHls"] is True
            assert next(s for s in stations if s["id"] == sid)["favorite"] is True
            request(base, "DELETE", f"/api/v1/stations/{sid}", owner)
            request(base, "PUT", f"/api/v1/stations/{sid}/favorite", owner, {"favorite": True}, expected=404)
            assert len(request(base, "GET", "/api/v1/stations", owner)["items"]) == 2
            feed = request(base, "POST", "/api/v1/podcasts", owner, {"feedUrl": upstream + "/rss"})
            assert feed["podcast"]["title"] == "Owned test feed"
    print(f"PASS {binary.name}: {mode}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binaries", nargs="+", type=Path)
    args = parser.parse_args()
    (ROOT / ".temp").mkdir(exist_ok=True)
    fixture = ThreadingHTTPServer(("127.0.0.1", 0), SourceHandler)
    fixture.daemon_threads = True
    thread = threading.Thread(target=fixture.serve_forever, daemon=True)
    thread.start()
    try:
        upstream = f"http://127.0.0.1:{fixture.server_port}"
        for binary in args.binaries:
            binary = binary.resolve(strict=True)
            for mode in ("unset", "empty", "whitespace", "configured"):
                exercise(binary, mode, upstream)
    finally:
        fixture.shutdown()
        fixture.server_close()
        thread.join(timeout=3)
    print("All content-scope smoke scenarios passed.")


if __name__ == "__main__":
    main()
