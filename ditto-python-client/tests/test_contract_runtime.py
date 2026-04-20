import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import unquote

from ditto_client import DittoHttpClient


class _ContractHandler(BaseHTTPRequestHandler):
    store = {}
    version = 0

    def _send_json(self, code: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/ping":
            self._send_json(200, {"pong": True})
            return
        if self.path.startswith("/key/"):
            key = unquote(self.path[len("/key/"):])
            entry = self.store.get(key)
            if entry is None:
                self.send_response(404)
                self.end_headers()
                return
            self._send_json(200, entry)
            return
        self.send_response(404)
        self.end_headers()

    def do_PUT(self):
        if not self.path.startswith("/key/"):
            self.send_response(404)
            self.end_headers()
            return
        key = unquote(self.path[len("/key/"):].split("?", 1)[0])
        size = int(self.headers.get("Content-Length", "0"))
        value = self.rfile.read(size).decode("utf-8")
        type(self).version += 1
        self.store[key] = {"value": value, "version": type(self).version}
        self._send_json(200, {"version": type(self).version})

    def do_DELETE(self):
        if not self.path.startswith("/key/"):
            self.send_response(404)
            self.end_headers()
            return
        key = unquote(self.path[len("/key/"):])
        if key not in self.store:
            self.send_response(404)
            self.end_headers()
            return
        del self.store[key]
        self.send_response(204)
        self.end_headers()

    def do_POST(self):
        if self.path != "/keys/delete-by-pattern":
            self.send_response(404)
            self.end_headers()
            return
        size = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(size).decode("utf-8") or "{}")
        prefix = str(payload.get("pattern", "")).rstrip("*")
        deleted = 0
        for key in list(self.store.keys()):
            if key.startswith(prefix):
                del self.store[key]
                deleted += 1
        self._send_json(200, {"deleted": deleted})

    def log_message(self, format, *args):
        return


class ContractRuntimeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _ContractHandler.store = {}
        _ContractHandler.version = 0
        cls.httpd = HTTPServer(("127.0.0.1", 0), _ContractHandler)
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.client = DittoHttpClient(host="127.0.0.1", port=cls.httpd.server_port)
        contract_path = Path(__file__).resolve().parents[2] / "contracts" / "core-ops.contract.json"
        cls.contract = json.loads(contract_path.read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def test_core_ops_contract_runtime(self):
        for case in self.contract["cases"]:
            op = case["operation"]
            if op == "ping":
                self.assertEqual(self.client.ping(), case["expect"]["value"])
            elif op == "set_get":
                self.client.set(case["inputs"]["key"], case["inputs"]["value"], case["inputs"]["ttl_secs"])
                got = self.client.get(case["inputs"]["key"])
                self.assertIsNotNone(got)
                self.assertEqual(got.value.decode("utf-8"), case["expect"]["value_equals"])
            elif op == "delete":
                deleted = self.client.delete(case["inputs"]["key"])
                self.assertEqual(deleted, case["expect"]["value"])
            elif op == "delete_by_pattern":
                self.client.set("contract:prefix:a", "a")
                self.client.set("contract:prefix:b", "b")
                out = self.client.delete_by_pattern(case["inputs"]["pattern"])
                self.assertGreaterEqual(out.deleted, case["expect"]["min"])
            else:
                self.fail(f"unsupported operation in contract: {op}")


if __name__ == "__main__":
    unittest.main()
