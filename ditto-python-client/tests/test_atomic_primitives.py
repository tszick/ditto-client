from __future__ import annotations

import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ditto_client.types import DittoErrorCode
from ditto_client.wire import (
    decode_response,
    decode_client_request_variant,
    encode_counter_response_inner,
    encode_incr,
    encode_set_nx,
    encode_set_nx_response_inner,
    frame_client_response,
    REQUEST_FIELDS,
    RESPONSE_FIELDS,
)


def _strip(framed: bytes) -> bytes:
    return framed[4:]


def _server_set_nx(created: bool, version: int) -> bytes:
    inner = encode_set_nx_response_inner(created, version)
    return _strip(frame_client_response(RESPONSE_FIELDS["SET_NX"], inner))


def _server_counter(value: int, version: int) -> bytes:
    inner = encode_counter_response_inner(value, version)
    return _strip(frame_client_response(RESPONSE_FIELDS["COUNTER"], inner))


class AtomicPrimitiveWireTests(unittest.TestCase):
    def test_decode_set_nx_created(self) -> None:
        resp = decode_response(_server_set_nx(True, 42))
        self.assertEqual("SetNx", resp.type)
        self.assertTrue(resp.created)
        self.assertEqual(42, resp.version)

    def test_decode_set_nx_existing_keeps_version_without_create(self) -> None:
        resp = decode_response(_server_set_nx(False, 7))
        self.assertEqual("SetNx", resp.type)
        self.assertFalse(resp.created)
        self.assertEqual(7, resp.version)

    def test_decode_counter_including_negative_and_extremes(self) -> None:
        for value in (-5, 0, 1, -(2 ** 63), 2 ** 63 - 1):
            resp = decode_response(_server_counter(value, 3))
            self.assertEqual("Counter", resp.type)
            self.assertEqual(value, resp.value)
            self.assertEqual(3, resp.version)

    def test_set_nx_request_routes_to_opcode_10_reusing_set_request(self) -> None:
        field, _inner = decode_client_request_variant(_strip(encode_set_nx("k", b"v", 60, None)))
        self.assertEqual(REQUEST_FIELDS["SET_NX"], field)

    def test_incr_encodes_negative_delta_as_twos_complement_varint(self) -> None:
        # proto int64 -1 == 10-byte 0xFF...0x01 varint (NOT zigzag).
        frame = encode_incr("k", -1)
        needle = bytes([0x10, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01])
        self.assertIn(needle, frame)

    def test_incr_request_routes_to_opcode_11(self) -> None:
        field, _inner = decode_client_request_variant(_strip(encode_incr("k", 5)))
        self.assertEqual(REQUEST_FIELDS["INCR"], field)

    def test_error_code_mapping_covers_atomic_codes(self) -> None:
        from ditto_client.wire import encode_error_response_inner

        cases = [
            (11, DittoErrorCode.UNSUPPORTED_REQUEST),
            (12, DittoErrorCode.TYPE_MISMATCH),
            (13, DittoErrorCode.OVERFLOW),
        ]
        for idx, want in cases:
            inner = encode_error_response_inner(idx, "x")
            resp = decode_response(_strip(frame_client_response(RESPONSE_FIELDS["ERROR"], inner)))
            self.assertEqual("Error", resp.type)
            self.assertEqual(want, resp.code)


import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from ditto_client import DittoCounterResult, DittoError, DittoErrorCode, DittoHttpClient, DittoSetNxResult


class _AtomicHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args) -> None:  # silence test server logging
        pass

    def _send(self, status, payload=None):
        self.send_response(status)
        if payload is not None:
            self.send_header("Content-Type", "application/json")
        self.end_headers()
        if payload is not None:
            self.wfile.write(json.dumps(payload).encode())

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        _ = self.rfile.read(length)
        if self.path == "/key/new?nx=1&ttl=60":
            self._send(201, {"created": True, "version": "1"})
        elif self.path == "/key/dupe?nx=1":
            # SET_NX on an existing key MUST be 200 (not 409).
            self._send(200, {"created": False, "version": "9"})
        elif self.path == "/key/counter/incr":
            self._send(200, {"value": "5", "version": "2"})
        elif self.path == "/key/legacy/incr":
            # An old dittod without the route would 404; new returns 501.
            self._send(501, {"error": "UnsupportedRequest", "message": "INCR not supported"})
        elif self.path == "/key/badtype/incr":
            self._send(409, {"error": "TypeMismatch", "message": "not a counter"})
        else:
            self._send(500, {"message": f"unexpected {self.path}"})


class AtomicPrimitiveHttpTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _AtomicHandler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.client = DittoHttpClient(host="127.0.0.1", port=self.port)

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def test_set_nx_created_returns_201_parsed(self) -> None:
        result = self.client.set_nx("new", "v", ttl_secs=60)
        self.assertEqual(DittoSetNxResult(created=True, version=1), result)

    def test_set_nx_existing_returns_200_not_conflict(self) -> None:
        result = self.client.set_nx("dupe", "v")
        self.assertEqual(DittoSetNxResult(created=False, version=9), result)

    def test_incr_parses_string_int64(self) -> None:
        result = self.client.incr("counter", 5)
        self.assertEqual(DittoCounterResult(value=5, version=2), result)

    def test_incr_unsupported_normalizes_to_unsupported_request(self) -> None:
        with self.assertRaises(DittoError) as ctx:
            self.client.incr("legacy", 1)
        self.assertEqual(DittoErrorCode.UNSUPPORTED_REQUEST, ctx.exception.code)

    def test_incr_type_mismatch_surfaces_as_type_mismatch(self) -> None:
        with self.assertRaises(DittoError) as ctx:
            self.client.incr("badtype", 1)
        self.assertEqual(DittoErrorCode.TYPE_MISMATCH, ctx.exception.code)


if __name__ == "__main__":
    unittest.main()
