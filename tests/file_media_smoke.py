#!/usr/bin/env python3
"""Black-box test for mounted/local file audio. Only spawns loopback services."""
import argparse
import contextlib
import json
import os
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(base, method, path, *, token=None, body=None, range_value=None, status=200):
    headers = {"Accept-Encoding": "identity"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    if range_value is not None:
        headers["Range"] = range_value
    payload = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body).encode()
    req = urllib.request.Request(base + path, headers=headers, data=payload, method=method)
    try:
        res = NO_PROXY.open(req, timeout=8)
    except urllib.error.HTTPError as error:
        res = error
    with res:
        assert res.status == status, (method, path.split("?", 1)[0], res.status, status)
        return res.headers, res.read()


def api(base, method, path, **kwargs):
    return json.loads(request(base, method, "/api/v1" + path, **kwargs)[1])


@contextlib.contextmanager
def server(binary, workdir, db_env, **options):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    env = {**db_env, **options, "APP_ADDR": f"127.0.0.1:{port}"}
    base = f"http://127.0.0.1:{port}"
    with (workdir / "server.log").open("ab") as log:
        process = subprocess.Popen([str(binary), "-env-file", str(workdir / "absent.env")],
                                   env=env, cwd=workdir, stdout=log, stderr=log)
        try:
            deadline = time.monotonic() + 10
            while True:
                assert process.poll() is None, f"fixture server failed, see {workdir}/server.log"
                try:
                    request(base, "GET", "/healthz")
                    break
                except (urllib.error.URLError, TimeoutError):
                    if time.monotonic() >= deadline:
                        raise
                    threading.Event().wait(0.03)
            yield base
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
                raise AssertionError("the fixture process did not shut down cleanly")
    assert process.returncode == 0


def test(binary):
    work = Path(tempfile.mkdtemp(prefix="file-media-smoke-", dir=ROOT / ".temp"))
    media = work / "media"
    (media / "测试").mkdir(parents=True)
    filename = "测试/有 空格+号.ogg"
    data = bytes(range(256)) * 8
    (media / filename).write_bytes(data)
    (media / "README.txt").write_text("not audio")
    large = 64 * 1024 * 1024
    with (media / "large.wav").open("wb") as fp:
        fp.seek(large - 4)
        fp.write(b"tail")
    env = {key: val for key, val in os.environ.items() if not key.startswith("FILE_MEDIA_")}
    env.update(APP_ENV="development", APP_DATA_DIR=str(work), DATABASE_PATH=str(work / "db.sqlite"),
               JWT_SECRET=secrets.token_hex(32), SIGNUP_INVITE_CODE="", WORKER_THREADS="4")
    with server(binary, work, env) as base:
        owner = api(base, "POST", "/auth/register", status=201, body={
            "email": "owner@example.test", "password": secrets.token_urlsafe(24)})
        other = api(base, "POST", "/auth/register", status=201, body={
            "email": "other@example.test", "password": secrets.token_urlsafe(24)})
        owner_token = owner["token"]["accessToken"]
        other_token = other["token"]["accessToken"]
        assert api(base, "GET", "/media/sources", token=owner_token) == {"sources": []}
    with server(binary, work, env, FILE_MEDIA_ROOT=str(media), FILE_MEDIA_OWNER_ID=owner["user"]["id"]) as base:
        api(base, "GET", "/media/sources", status=401)
        assert api(base, "GET", "/media/sources", token=other_token) == {"sources": []}
        assert [source["id"] for source in api(base, "GET", "/media/sources", token=owner_token)["sources"]] == ["local"]
        api(base, "GET", "/media/entries?source=local", token=other_token, status=404)
        root = api(base, "GET", "/media/entries?source=local", token=owner_token)["entries"]
        assert [(row["name"], row["directory"]) for row in root] == [("测试", True), ("large.wav", False)]
        child = urllib.parse.urlencode({"source": "local", "path": "测试"})
        assert api(base, "GET", "/media/entries?" + child, token=owner_token)["entries"] == [
            {"name": "有 空格+号.ogg", "size": len(data), "directory": False}]
        target = {"source": "local", "path": filename}
        api(base, "POST", "/media/ticket", token=other_token, body=target, status=404)
        ticket = api(base, "POST", "/media/ticket", token=owner_token, body=target)["ticket"]
        assert len(ticket) == 64
        request(base, "GET", "/api/v1/media/stream", status=403)
        stream = "/api/v1/media/stream?" + urllib.parse.urlencode({"ticket": ticket})
        h, content = request(base, "HEAD", stream)
        assert h["Content-Length"] == str(len(data)) and h["Accept-Ranges"] == "bytes" and content == b""
        h, content = request(base, "GET", stream)
        assert h["Content-Type"] == "audio/ogg" and content == data
        h, content = request(base, "GET", stream, range_value="bytes=110-325", status=206)
        assert h["Content-Range"] == f"bytes 110-325/{len(data)}" and content == data[110:326]
        h, content = request(base, "GET", stream, range_value="bytes=-12", status=206)
        assert content == data[-12:]
        h, _ = request(base, "GET", stream, range_value=f"bytes={len(data)}-", status=416)
        assert h["Content-Range"] == f"bytes */{len(data)}"
        large_ticket = api(base, "POST", "/media/ticket", token=owner_token,
                           body={"source": "local", "path": "large.wav"})["ticket"]
        _, content = request(base, "GET", "/api/v1/media/stream?ticket=" + large_ticket,
                             range_value=f"bytes={large - 4}-", status=206)
        assert content == b"tail"
    with server(binary, work, env, FILE_MEDIA_ROOT=str(media), FILE_MEDIA_OWNER_ID=owner["user"]["id"]) as base:
        request(base, "GET", stream, status=403)
    assert ticket not in (work / "server.log").read_text()
    print("PASS file media: opt-in, owner scope, UTF-8, length, ranges, large-file seek, restart, log redaction")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("binary", type=Path)
    test(parser.parse_args().binary.resolve(strict=True))
