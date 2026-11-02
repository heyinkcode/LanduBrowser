import importlib.util
import json
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path
import threading
import unittest


path = Path(__file__).resolve().parents[1] / "scripts/serve-verification.py"
spec = importlib.util.spec_from_file_location("landu_probe", path)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class VerificationServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), probe.ProbeHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=3)

    def get(self, target, headers=None):
        connection = HTTPConnection("127.0.0.1", self.port, timeout=3)
        try:
            connection.request("GET", target, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_page_and_assets_are_served_with_client_hint_opt_in(self):
        for route in probe.FILES:
            status, headers, body = self.get(route)
            self.assertEqual(200, status, route)
            self.assertTrue(body, route)
            self.assertEqual("no-store", headers["Cache-Control"])
            self.assertIn("Sec-CH-Device-Memory", headers["Accept-CH"])

    def test_echo_reports_missing_hints_as_null(self):
        status, _, body = self.get("/request-headers")
        self.assertEqual(200, status)
        self.assertEqual(self.empty_headers(), json.loads(body))

    def test_echo_limits_data_to_the_probe_headers(self):
        status, _, body = self.get("/request-headers", {
            "Sec-CH-Device-Memory": "4", "Device-Memory": "8", "Accept-Language": "en-US,en;q=0.9", "DNT": "1", "Cookie": "private=secret",
        })
        self.assertEqual(200, status)
        expected = self.empty_headers()
        expected.update({"sec-ch-device-memory": "4", "device-memory": "8", "accept-language": "en-US,en;q=0.9", "dnt": "1"})
        self.assertEqual(expected, json.loads(body))
        self.assertNotIn(b"secret", body)

    @staticmethod
    def empty_headers():
        return {"sec-ch-device-memory": None, "device-memory": None, "accept-language": None, "dnt": None,
                "user-agent": None, "sec-ch-ua": None, "sec-ch-ua-mobile": None, "sec-ch-ua-platform": None,
                "sec-ch-ua-platform-version": None, "sec-ch-ua-model": None, "sec-ch-ua-full-version-list": None}

    def test_identity_headers_preserve_structured_values_without_reflecting_private_data(self):
        sent = {"User-Agent": "test-agent", "Sec-CH-UA-Model": '"Pixel 8"', "Sec-CH-UA-Platform-Version": '"15.0.0"',
                "Sec-CH-UA-Full-Version-List": '"Chromium";v="156.0.8078.48"', "Authorization": "private-token"}
        status, _, body = self.get("/request-headers", sent)
        expected = self.empty_headers()
        expected.update({k.lower(): v for k, v in sent.items() if k != "Authorization"})
        self.assertEqual(200, status)
        self.assertEqual(expected, json.loads(body))
        self.assertNotIn(b"private-token", body)

    def test_kernel_reference_is_explicitly_source_metadata_not_a_device_result(self):
        status, _, body = self.get("/expected-kernel")
        reference = json.loads(body)
        self.assertEqual(200, status)
        self.assertEqual({"kind", "version", "revision"}, set(reference))
        self.assertEqual("source-reference-not-installed-package", reference["kind"])
        self.assertRegex(reference["version"], r"^\d+\.\d+\.\d+\.\d+$")
        self.assertRegex(reference["revision"], r"^[0-9a-f]{40}$")

    def test_navigation_does_not_execute_reflected_header_values(self):
        status, _, body = self.get("/navigation-check", {"Device-Memory": "</script><script>alert(1)</script>"})
        self.assertEqual(200, status)
        self.assertNotIn(b"<script>alert", body)
        self.assertIn(b"\\u003c/script>", body)

    def test_unknown_paths_and_traversal_never_expose_source(self):
        for route in ("/../upstream-lock.json", "/%2e%2e/upstream-lock.json", "/scripts/serve-verification.py", "/frame.html/../"):
            status, _, body = self.get(route)
            self.assertEqual(404, status, route)
            self.assertEqual(b"Not found", body)

    def test_unexpected_host_is_rejected(self):
        status, _, _ = self.get("/", {"Host": "unrelated.example"})
        self.assertEqual(403, status)


if __name__ == "__main__":
    unittest.main()
