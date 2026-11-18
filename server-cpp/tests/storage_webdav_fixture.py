"""Run an isolated TLS fixture with a storage_webdav_test binary as argv[1]."""

import base64
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import threading
from xml.sax.saxutils import escape

ROOT = "/isolated%20root/"
AUDIO = "%E9%9F%B3%20%E5%A3%B0%25%3F.mp3"
AUTH = "Basic " + base64.b64encode(b"fixture:fake:password").decode("ascii")
DATA = b"abcdefgh"


def prop(value, status="200 OK"):
    return f"<D:propstat><D:prop>{value}</D:prop><D:status>HTTP/1.1 {status}</D:status></D:propstat>"


def response(href, props=None):
    if props is None:
        props = prop("<D:resourcetype/><D:getcontentlength>1</D:getcontentlength>")
    return f"<D:response><D:href>{escape(href)}</D:href>{props}</D:response>"


def multistatus(entries):
    return ('<D:multistatus xmlns:D="DAV:" xmlns:E="urn:other">'
            + "".join(entries) + "</D:multistatus>").encode()


class DavHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def handle(self):
        try:
            super().handle()
        except (ConnectionResetError, BrokenPipeError):
            # A peer may drop the TLS connection before sending an HTTP request.
            pass

    def send(self, status, body=b"", headers=(), length=True):
        self.send_response(status)
        self.send_header("Connection", "close")
        if length:
            self.send_header("Content-Length", str(len(body)))
        for key, value in headers:
            self.send_header(key, value)
        self.end_headers()
        self.close_connection = True
        if body and self.command != "HEAD":
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                pass  # The client may abort after rejecting headers or body.

    def chunked(self, status, body, headers=(), length=False):
        wire = f"{len(body):X}\r\n".encode() + body + b"\r\n0\r\n\r\n"
        self.send(status, wire, [("Transfer-Encoding", "chunked"), *headers], length)

    def route(self):
        if not self.path.startswith(ROOT) or self.path == ROOT + "must-not-fetch":
            self.server.unexpected.append((self.command, self.path))
            self.send(404)
            return None
        if self.headers.get("Authorization") != AUTH:
            self.send(401, b"denied", [("WWW-Authenticate", 'Basic realm="loopback"')])
            return None
        return self.path[len(ROOT):]

    def redirect(self):
        self.send(302, b"not audio", [("Location", ROOT + "must-not-fetch")])

    def do_PROPFIND(self):
        rel = self.route()
        if rel is None:
            return
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.headers.get("Depth") != "1" or not all(tag in body for tag in
                                                       (b"resourcetype", b"getcontentlength")):
            return self.send(400)
        origin = f"https://127.0.0.1:{self.server.server_port}"
        if rel == "redirect/":
            return self.redirect()
        if rel == "huge/":
            return self.send(207, headers=[("Content-Length", "1048577")], length=False)
        if rel == "chunked-huge/":
            return self.chunked(207, b"x" * (1024 * 1024 + 1))
        if rel == "badxml/":
            return self.send(207, b"<garbage")
        if rel in {"many/", "max/", "many-invalid/"}:
            prefix = ROOT + rel
            paths = [prefix] + [prefix + str(i) for i in range(500 if rel == "max/" else 501)]
            if rel == "many-invalid/":
                paths[1:] = ["/other/one"] * 501
            return self.send(207, multistatus(map(response, paths)))
        if rel in {"dtd/", "external-dtd/", "utf16-dtd/"}:
            entity_url = (origin + ROOT + "must-not-fetch").encode()
            declaration = (b'<!DOCTYPE D:multistatus SYSTEM "' + entity_url + b'">'
                           if rel == "external-dtd/" else b'<!DOCTYPE D:multistatus [<!ENTITY e "x">]>')
            xml = declaration + multistatus([])
            return self.send(207, xml.decode().encode("utf-16") if rel == "utf16-dtd/" else xml)
        if rel == "dir/":
            return self.send(207, multistatus(map(response, [ROOT + "dir/inside.mp3",
                                                            ROOT + "dir/deeper/out", ROOT + "sibling"])))
        if rel != "":
            self.server.unexpected.append((self.command, self.path))
            return self.send(404)
        children = [
            response(ROOT),
            response(ROOT + "dir/", prop("<D:resourcetype><D:collection/></D:resourcetype>")
                     + prop("<D:getcontentlength/>", "404 Not Found")),
            response(ROOT + AUDIO, prop("<D:resourcetype/><D:getcontentlength>8</D:getcontentlength>")),
            response(origin + ROOT + "abs%2bname"),
            response(ROOT + "abs%2Bname"),
            response(ROOT + "failed", prop("<D:resourcetype/>", "403 Forbidden")),
            response(ROOT + "foreign-type", prop("<E:resourcetype/>")),
            response(ROOT + "failed-response", prop("<D:resourcetype/>")
                     + "<D:status>HTTP/1.1 404 Not Found</D:status>"),
        ]
        children.extend(map(response, [
            "https://example.invalid" + ROOT + "other-host", "https://127.0.0.1:1" + ROOT + "other-port",
            origin.replace("https:", "http:") + ROOT + "plain-http",
            origin.replace("https://", "https://guest@") + ROOT + "userinfo",
            "//127.0.0.1" + ROOT + "network-path", "relative-href", "/isolated%20root-other/not-child",
            *(ROOT + name for name in ("dir/grandchild", "%2e%2e", "%2e%2e/not-child", "encoded%2fslash",
                                       "encoded%5cbackslash", "double%252fencoded", "encoded%00null",
                                       "broken%2", "%C0%AF", "item?query", "item#fragment")),
        ]))
        return self.send(207, multistatus(children))

    def do_HEAD(self):
        rel = self.route()
        if rel is None:
            return
        if rel == "badhead":
            return self.send(200, headers=[("Content-Length", "-1")], length=False)
        if rel == "unknown-length":
            return self.send(200, length=False)
        if rel == "redirect-head":
            return self.redirect()
        if rel == "empty":
            return self.send(200)
        if rel not in {AUDIO, "full-200", "chunked", "wrong-range", "wrong-total", "missing-range",
                        "wrong-length", "encoded", "multipart", "ambiguous-length", "ambiguous-framing",
                        "404", "redirect-read", "truncated", "oversized"}:
            self.server.unexpected.append((self.command, self.path))
            return self.send(404)
        return self.send(200, DATA)

    def do_GET(self):
        rel = self.route()
        if rel is None:
            return
        range_ = self.headers.get("Range", "")
        if not range_.startswith("bytes=") or self.headers.get("Accept-Encoding") != "identity":
            self.server.unexpected.append((self.command, self.path))
            return self.send(400)
        first, last = map(int, range_[len("bytes="):].split("-"))
        body = DATA[first:last + 1]
        headers = [("Content-Range", f"bytes {first}-{last}/{len(DATA)}")]
        if rel == "full-200":
            return self.send(200, DATA)
        if rel == "404":
            return self.send(404, b"not audio")
        if rel == "redirect-read":
            return self.redirect()
        if rel == "wrong-range":
            return self.send(206, body, [("Content-Range", "bytes 0-2/8")])
        if rel == "wrong-total":
            return self.send(206, body, [("Content-Range", "bytes 2-4/9")])
        if rel == "missing-range":
            return self.send(206, body)
        if rel == "wrong-length":
            return self.send(206, body + b"!", headers)
        if rel == "encoded":
            return self.send(206, body, headers + [("Content-Encoding", "gzip")])
        if rel == "multipart":
            return self.send(206, body, headers + [("Content-Type", "multipart/byteranges; boundary=x")])
        if rel == "ambiguous-length":
            return self.send(206, body, headers + [("Content-Length", "3")])
        if rel in {"chunked", "ambiguous-framing", "oversized"}:
            return self.chunked(206, body + (b"!" if rel == "oversized" else b""),
                                headers, length=(rel == "ambiguous-framing"))
        if rel == "truncated":
            return self.send(206, body[:1], headers + [("Content-Length", "3")], length=False)
        return self.send(206, body, headers)


def main():
    binary = Path(sys.argv[1]).resolve()
    workspace = Path(__file__).resolve().parents[2]
    scratch = workspace / ".temp"
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="webdav-tls-", dir=scratch) as temp:
        cert, key = (str(Path(temp) / name) for name in ("cert.pem", "key.pem"))
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1",
            "-keyout", key, "-out", cert,
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=10)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
        with ThreadingHTTPServer(("127.0.0.1", 0), DavHandler) as server:
            server.unexpected = []
            server.socket = ctx.wrap_socket(server.socket, server_side=True)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                proxy = "http://127.0.0.1:1"
                env = dict(os.environ, http_proxy=proxy, https_proxy=proxy, all_proxy=proxy,
                           HTTP_PROXY=proxy, HTTPS_PROXY=proxy, ALL_PROXY=proxy, no_proxy="", NO_PROXY="")
                subprocess.run([binary, f"https://127.0.0.1:{server.server_port}", cert],
                               check=True, env=env, timeout=35)
                if server.unexpected:
                    raise RuntimeError(f"Unexpected DAV requests: {server.unexpected!r}")
            finally:
                server.shutdown()
                thread.join()


if __name__ == "__main__":
    main()
