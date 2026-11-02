#!/usr/bin/env python3
"""USB-only development probe. No external services, uploads, or disk writes."""

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1] / "verification"
FILES = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/index.html": ("index.html", "text/html; charset=utf-8"),
    "/frame.html": ("frame.html", "text/html; charset=utf-8"),
    **{f"/{name}.js": (f"{name}.js", "text/javascript; charset=utf-8")
       for name in ("verify", "values", "fonts", "canvas", "webgl", "audio", "audio-worklet", "rects", "media", "interaction", "region-checks", "dnt-checks", "identity", "gpu", "geolocation", "frame", "worker", "service-worker", "navigation")},
}
HINTS = ("Sec-CH-Device-Memory", "Device-Memory", "Sec-CH-UA-Platform-Version", "Sec-CH-UA-Model", "Sec-CH-UA-Full-Version-List")
REQUEST_HEADERS = (*HINTS, "Accept-Language", "DNT", "User-Agent", "Sec-CH-UA", "Sec-CH-UA-Mobile", "Sec-CH-UA-Platform")


class ProbeHandler(BaseHTTPRequestHandler):
    server_version = "LanduLocalProbe/1"

    def respond(self, status, body, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Accept-CH", ", ".join(HINTS))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'self'")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        # Restrict browser access to the loopback name used by adb reverse.
        port = self.server.server_address[1]
        if self.headers.get("Host") not in (f"127.0.0.1:{port}", f"localhost:{port}"):
            self.respond(403, b"Loopback host required", "text/plain")
            return
        route = urlsplit(self.path).path
        headers = {key.lower(): self.headers.get(key) for key in REQUEST_HEADERS}
        if route == "/expected-kernel":
            try:
                lock = json.loads((ROOT.parent / "upstream-lock.json").read_text(encoding="utf-8"))
                body = json.dumps({"version": lock["version"], "revision": lock["revision"],
                                   "kind": "source-reference-not-installed-package"}).encode()
                self.respond(200, body, "application/json")
            except (OSError, ValueError, KeyError):
                self.respond(500, b"Source reference unavailable", "text/plain")
        elif route == "/request-headers":
            self.respond(200, json.dumps(headers).encode(), "application/json")
        elif route == "/navigation-check":
            # Encode untrusted headers as data, never executable JavaScript.
            data = json.dumps(headers).replace("<", "\\u003c").replace("&", "\\u0026")
            body = ("<!doctype html><meta charset=utf-8><title>Navigation probe</title>"
                    f'<script type="application/json" id="headers">{data}</script>'
                    '<script src="navigation.js"></script>')
            self.respond(200, body.encode(), "text/html; charset=utf-8")
        elif route in FILES:
            filename, content_type = FILES[route]
            file = ROOT / filename
            if file.is_symlink() or not file.is_file():
                self.respond(404, b"Not found", "text/plain")
                return
            self.respond(200, file.read_bytes(), content_type)
        else:
            self.respond(404, b"Not found", "text/plain")

    def log_message(self, format, *args):
        # Don't log URL query strings or request headers from diagnostics.
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Use a port between 1024 and 65535")
    with ThreadingHTTPServer(("127.0.0.1", args.port), ProbeHandler) as server:
        print(f"Landu verification: http://127.0.0.1:{args.port}/", flush=True)
        print("Loopback only. Stop with Ctrl+C. No public hosting is required.", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
