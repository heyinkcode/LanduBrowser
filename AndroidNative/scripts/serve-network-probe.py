#!/usr/bin/env python3
"""Loopback-only HTTP/SOCKS5 test endpoint. Never forwards to any other host."""

import argparse
import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import socketserver
import struct
from urllib.parse import urlsplit


HOST = "landu-network-check.test"
ROUTES = ("/", "/network-check", "/network-check.json")


class ProbeHttpHandler(BaseHTTPRequestHandler):
    server_version = "LanduNetworkProbe/1"
    sys_version = ""
    absolute_target = True

    def respond(self, status, body, content_type="text/plain; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        try:
            target = urlsplit(self.path)
            host = self.headers.get("Host")
            if self.absolute_target:
                allowed = target.scheme == "http" and target.hostname == HOST and target.port in (None, 80) \
                    and target.username is None and target.password is None
            else:
                allowed = not target.scheme and not target.netloc
            if not allowed or host not in (HOST, HOST + ":80") or target.path not in ROUTES or target.query or target.fragment:
                self.respond(403, b"Only the local probe endpoint is allowed")
                return
        except ValueError:
            self.respond(400, b"Invalid request target")
            return
        report = {"format": 1, "kind": "landu-local-network-probe", "proxyIdentity": self.server.identity,
                  "proxyProtocol": self.server.protocol, "target": HOST, "requestReachedThisProxy": True,
                  "internetForwarded": False, "androidEnvironmentVerified": False}
        data = json.dumps(report, ensure_ascii=False, indent=2)
        if target.path.endswith(".json"):
            self.respond(200, data.encode(), "application/json")
        else:
            body = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                    "<title>岚渡网络检查</title><style>body{font:16px/1.7 system-ui,sans-serif;background:#f6f8fc;"
                    "color:#202632;max-width:800px;margin:40px auto;padding:24px}main{background:white;border:1px solid #e3e7ef;"
                    "border-radius:16px;padding:24px}h1{font-size:24px;font-weight:500}pre{white-space:pre-wrap;overflow-wrap:anywhere}"
                    ".ok{color:#157347}</style><main><h1>岚渡网络检查</h1>"
                    f"<p class=ok>本次请求到达代理 {html.escape(self.server.identity)}。</p>"
                    "<p>切换环境后核对代理标识。此结果只证明本次 HTTP 请求经过该测试入口；其他连接和 WebRTC 仍需单独验收。</p>"
                    f"<pre>{html.escape(data)}</pre></main>")
            self.respond(200, body.encode(), "text/html; charset=utf-8")

    def do_CONNECT(self):
        self.respond(403, b"This fixture never opens outbound tunnels")

    def log_message(self, format, *args):
        pass


class SocksHttpHandler(ProbeHttpHandler):
    absolute_target = False


def read_exact(connection, count):
    result = bytearray()
    while len(result) < count:
        chunk = connection.recv(count - len(result))
        if not chunk:
            raise EOFError("Peer closed")
        result.extend(chunk)
    return bytes(result)


class SocksHandler(socketserver.BaseRequestHandler):
    def handle(self):
        connection = self.request
        connection.settimeout(5)
        try:
            version, count = read_exact(connection, 2)
            if version != 5 or not 1 <= count <= 16:
                return
            if 0 not in read_exact(connection, count):
                connection.sendall(b"\x05\xff")
                return
            connection.sendall(b"\x05\x00")
            version, command, reserved, address_type = read_exact(connection, 4)
            if version != 5 or command != 1 or reserved != 0 or address_type != 3:
                connection.sendall(b"\x05\x07\x00\x01" + b"\x00" * 6)
                return
            length = read_exact(connection, 1)[0]
            host = read_exact(connection, length).decode("ascii")
            port = struct.unpack("!H", read_exact(connection, 2))[0]
            if host.lower() != HOST or port != 80:
                connection.sendall(b"\x05\x02\x00\x01" + b"\x00" * 6)
                return
            # Establish a virtual connection to this fixture only. No DNS
            # lookup, upstream socket or public network operation is performed.
            connection.sendall(b"\x05\x00\x00\x01" + b"\x00" * 6)
            SocksHttpHandler(connection, self.client_address, self.server)
        except (EOFError, OSError, UnicodeError):
            pass


class SocksServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def create_server(protocol, port, identity):
    if protocol not in ("http", "socks5") or not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", identity):
        raise ValueError("Invalid protocol or identity")
    if not 0 <= port <= 65535:
        raise ValueError("Invalid port")
    server = ThreadingHTTPServer(("127.0.0.1", port), ProbeHttpHandler) if protocol == "http" \
        else SocksServer(("127.0.0.1", port), SocksHandler)
    server.identity = identity
    server.protocol = protocol
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", choices=("http", "socks5"), default="http")
    parser.add_argument("--port", type=int, default=8877)
    parser.add_argument("--identity", default="A")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Use a port between 1024 and 65535")
    with create_server(args.protocol, args.port, args.identity) as server:
        print(f"Proxy fixture: {args.protocol}://127.0.0.1:{args.port} identity={args.identity}", flush=True)
        print(f"Probe URL: http://{HOST}/  Stop with Ctrl+C. No Internet forwarding.", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
