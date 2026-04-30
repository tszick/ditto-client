from __future__ import annotations

import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ditto_client.types import DittoErrorCode
from ditto_client.wire import (
    decode_response,
    encode_error_response_inner,
    encode_watch_event_inner,
    frame_client_response,
    RESPONSE_FIELDS,
)


def _strip_length_prefix(framed: bytes) -> bytes:
    """The SDK's decode_response expects the envelope payload without the
    4-byte BE length prefix that frame_client_response emits."""
    return framed[4:]


def _server_error(idx: int, message: str = "x") -> bytes:
    inner = encode_error_response_inner(idx, message)
    return _strip_length_prefix(frame_client_response(RESPONSE_FIELDS["ERROR"], inner))


def _server_watch_event(key: str, value: bytes | None, version: int) -> bytes:
    inner = encode_watch_event_inner(key, value, version)
    return _strip_length_prefix(frame_client_response(RESPONSE_FIELDS["WATCH_EVENT"], inner))


class WireDecodeTests(unittest.TestCase):
    def test_error_code_mapping_includes_rate_limit_and_circuit_open(self) -> None:
        cases = [
            (7, DittoErrorCode.RATE_LIMITED),
            (8, DittoErrorCode.CIRCUIT_OPEN),
            (9, DittoErrorCode.NAMESPACE_QUOTA_EXCEEDED),
            (10, DittoErrorCode.AUTH_FAILED),
        ]
        for idx, want in cases:
            resp = decode_response(_server_error(idx))
            self.assertEqual("Error", resp.type)
            self.assertEqual(want, resp.code)

    def test_error_code_falls_back_to_internal_for_unknown_index(self) -> None:
        resp = decode_response(_server_error(99, "mystery"))
        self.assertEqual("Error", resp.type)
        self.assertEqual(DittoErrorCode.INTERNAL_ERROR, resp.code)
        self.assertEqual("mystery", resp.message)

    def test_watch_event_decode_none_and_some_value(self) -> None:
        resp_none = decode_response(_server_watch_event("watched-key", None, 42))
        self.assertEqual("WatchEvent", resp_none.type)
        self.assertIsNone(resp_none.value)
        self.assertEqual(42, resp_none.version)

        resp_some = decode_response(_server_watch_event("watched-key", b"value", 43))
        self.assertEqual("WatchEvent", resp_some.type)
        self.assertEqual(b"value", resp_some.value)
        self.assertEqual(43, resp_some.version)


if __name__ == "__main__":
    unittest.main()
