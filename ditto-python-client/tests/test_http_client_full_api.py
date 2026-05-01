import base64
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from ditto_client import DittoError, DittoErrorCode, DittoHttpClient


class FullApiHandler(BaseHTTPRequestHandler):
    seen = []

    def _send(self, status, payload=None):
        self.send_response(status)
        if payload is not None:
            self.send_header("Content-Type", "application/json")
        self.end_headers()
        if payload is not None:
            self.wfile.write(json.dumps(payload).encode())

    def _assert_auth(self):
        want = "Basic " + base64.b64encode(b"ditto:secret").decode()
        if self.headers.get("Authorization") != want:
            raise AssertionError(f"unexpected Authorization: {self.headers.get('Authorization')}")

    def _assert_equal(self, got, want):
        if got != want:
            raise AssertionError(f"got {got!r}, want {want!r}")

    def do_GET(self):
        self._assert_auth()
        self.seen.append(("GET", self.path))
        if self.path == "/ping":
            self._send(200, {"pong": True})
        elif self.path == "/key/ns-key":
            self._assert_equal(self.headers.get("X-Ditto-Namespace"), "tenant-a")
            self._send(200, {"value": "value", "version": 7})
        elif self.path == "/key/missing":
            self._send(404)
        elif self.path == "/key/failing":
            self._send(429, {"error": "RateLimited", "message": "slow down"})
        elif self.path == "/stats":
            self._send(200, {"node_id": "n1", "status": "active", "is_primary": True, "key_count": 2})
        else:
            self._send(500, {"message": f"unexpected {self.path}"})

    def do_PUT(self):
        self._assert_auth()
        self.seen.append(("PUT", self.path))
        self._assert_equal(self.path, "/key/ns-key?ttl=30")
        self._assert_equal(self.headers.get("X-Ditto-Namespace"), "tenant-a")
        self._assert_equal(self.headers.get("Content-Type"), "text/plain")
        body = self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode()
        self._assert_equal(body, "value")
        self._send(200, {"version": 7})

    def do_DELETE(self):
        self._assert_auth()
        self.seen.append(("DELETE", self.path))
        if self.path == "/key/ns-key":
            self._send(204)
        elif self.path == "/key/missing":
            self._send(404)
        else:
            self._send(500, {"message": f"unexpected {self.path}"})

    def do_POST(self):
        self._assert_auth()
        self.seen.append(("POST", self.path))
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode())
        if self.path == "/keys/delete-by-pattern":
            self._assert_equal(self.headers.get("X-Ditto-Namespace"), "tenant-a")
            self._assert_equal(body, {"pattern": "tenant:*"})
            self._send(200, {"deleted": 3})
        elif self.path == "/keys/ttl-by-pattern":
            self._assert_equal(body, {"pattern": "tenant:*", "ttl_secs": 45})
            self._send(200, {"updated": 4})
        else:
            self._send(500, {"message": f"unexpected {self.path}"})

    def log_message(self, *_):
        pass


class HttpClientFullApiTests(unittest.TestCase):
    def setUp(self):
        FullApiHandler.seen = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FullApiHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.client = DittoHttpClient(
            host="127.0.0.1",
            port=self.server.server_port,
            username="ditto",
            password="secret",
            strict_mode=True,
            timeout_secs=2.0,
        )

    def tearDown(self):
        self.client.close()
        self.server.shutdown()
        self.thread.join(timeout=2.0)
        self.server.server_close()

    def test_full_http_surface(self):
        self.assertTrue(self.client.ping())
        self.assertEqual(self.client.set("ns-key", "value", 30, "tenant-a").version, 7)
        got = self.client.get("ns-key", "tenant-a")
        self.assertEqual(got.value, b"value")
        self.assertEqual(got.version, 7)
        self.assertTrue(self.client.delete("ns-key"))
        self.assertFalse(self.client.delete("missing"))
        self.assertEqual(self.client.delete_by_pattern("tenant:*", "tenant-a").deleted, 3)
        self.assertEqual(self.client.set_ttl_by_pattern("tenant:*", 45).updated, 4)
        self.assertEqual(self.client.stats().node_id, "n1")
        self.assertIsNone(self.client.get("missing"))
        with self.assertRaises(DittoError) as raised:
            self.client.get("failing")
        self.assertEqual(raised.exception.code, DittoErrorCode.RATE_LIMITED)
        self.assertGreaterEqual(len(FullApiHandler.seen), 10)


if __name__ == "__main__":
    unittest.main()
