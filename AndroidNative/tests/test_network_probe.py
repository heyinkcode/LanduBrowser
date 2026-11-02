import importlib.util
import json
from pathlib import Path
import socket
import struct
import threading
import unittest


path = Path(__file__).resolve().parents[1] / "scripts/serve-network-probe.py"
spec = importlib.util.spec_from_file_location("landu_network_probe", path)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class NetworkProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.servers = [probe.create_server("http", 0, "A"), probe.create_server("http", 0, "B"),
                       probe.create_server("socks5", 0, "S")]
        cls.threads = []
        for server in cls.servers:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            cls.threads.append(thread)

    @classmethod
    def tearDownClass(cls):
        for server in cls.servers:
            server.shutdown()
            server.server_close()
        for thread in cls.threads:
            thread.join(timeout=3)

    def connect(self, server):
        connection = socket.socket()
        connection.settimeout(3)
        connection.connect(server.server_address)
        self.addCleanup(connection.close)
        return connection

    def response(self, connection, target, host=probe.HOST, method="GET"):
        connection.sendall(f"{method} {target} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n".encode())
        data = bytearray()
        while True:
            chunk = connection.recv(8192)
            if not chunk:
                break
            data.extend(chunk)
        headers, body = bytes(data).split(b"\r\n\r\n", 1)
        return int(headers.split(b" ")[1]), headers, body

    def socks_connect(self, host=probe.HOST, port=80):
        connection = self.connect(self.servers[2])
        connection.sendall(b"\x05\x01\x00")
        self.assertEqual(b"\x05\x00", probe.read_exact(connection, 2))
        encoded = host.encode()
        connection.sendall(b"\x05\x01\x00\x03" + bytes([len(encoded)]) + encoded + struct.pack("!H", port))
        return connection, probe.read_exact(connection, 10)

    def test_http_proxy_identities_are_independent(self):
        for server, identity in zip(self.servers[:2], ("A", "B")):
            status, headers, body = self.response(self.connect(server), f"http://{probe.HOST}/network-check.json")
            self.assertEqual(200, status)
            report = json.loads(body)
            self.assertEqual(identity, report["proxyIdentity"])
            self.assertEqual("http", report["proxyProtocol"])
            self.assertFalse(report["internetForwarded"])
            self.assertFalse(report["androidEnvironmentVerified"])
            self.assertIn(b"Cache-Control: no-store", headers)

    def test_http_refuses_other_hosts_and_tunnels(self):
        for target, host, method in (("http://example.com/", "example.com", "GET"),
                                     (f"http://{probe.HOST}/", "other.test", "GET"),
                                     (f"https://{probe.HOST}/", probe.HOST, "GET"),
                                     ("example.com:443", "example.com", "CONNECT")):
            status, _, _ = self.response(self.connect(self.servers[0]), target, host, method)
            self.assertEqual(403, status)

    def test_socks5_tunnel_reaches_only_virtual_local_probe(self):
        connection, reply = self.socks_connect()
        self.assertEqual(0, reply[1])
        status, _, body = self.response(connection, "/network-check.json")
        self.assertEqual(200, status)
        self.assertEqual("S", json.loads(body)["proxyIdentity"])
        self.assertEqual("socks5", json.loads(body)["proxyProtocol"])

    def test_socks5_rejects_other_destinations_and_ports(self):
        for host, port in (("example.com", 80), (probe.HOST, 443), ("127.0.0.1", 80)):
            _, reply = self.socks_connect(host, port)
            self.assertEqual(2, reply[1])

    def test_socks5_does_not_claim_authentication_support(self):
        connection = self.connect(self.servers[2])
        connection.sendall(b"\x05\x01\x02")
        self.assertEqual(b"\x05\xff", probe.read_exact(connection, 2))

    def test_probe_html_has_readable_identity_and_scope(self):
        status, _, body = self.response(self.connect(self.servers[0]), f"http://{probe.HOST}/")
        self.assertEqual(200, status)
        self.assertIn("本次请求到达代理 A".encode(), body)
        self.assertIn("其他连接和 WebRTC 仍需单独验收".encode(), body)


if __name__ == "__main__":
    unittest.main()
