from __future__ import annotations

import struct
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ditto_client.bincode import decode_response
from ditto_client.types import DittoErrorCode


def pack_string(raw: bytes) -> bytes:
    return struct.pack("<Q", len(raw)) + raw


class BincodeDecodeTests(unittest.TestCase):
    def test_error_code_mapping_includes_rate_limit_and_circuit_open(self) -> None:
        cases = [
            (7, DittoErrorCode.RATE_LIMITED),
            (8, DittoErrorCode.CIRCUIT_OPEN),
            (9, DittoErrorCode.NAMESPACE_QUOTA_EXCEEDED),
            (10, DittoErrorCode.AUTH_FAILED),
        ]
        for idx, want in cases:
            payload = (
                struct.pack("<I", 6)
                + struct.pack("<I", idx)
                + pack_string(b"x")
            )
            resp = decode_response(payload)
            self.assertEqual("Error", resp.type)
            self.assertEqual(want, resp.code)

    def test_watch_event_decode_none_and_some_value(self) -> None:
        payload_none = (
            struct.pack("<I", 9)
            + pack_string(b"watched-key")
            + struct.pack("B", 0)
            + struct.pack("<Q", 42)
        )
        resp_none = decode_response(payload_none)
        self.assertEqual("WatchEvent", resp_none.type)
        self.assertIsNone(resp_none.value)
        self.assertEqual(42, resp_none.version)

        payload_some = (
            struct.pack("<I", 9)
            + pack_string(b"watched-key")
            + struct.pack("B", 1)
            + pack_string(b"value")
            + struct.pack("<Q", 43)
        )
        resp_some = decode_response(payload_some)
        self.assertEqual("WatchEvent", resp_some.type)
        self.assertEqual(b"value", resp_some.value)
        self.assertEqual(43, resp_some.version)


if __name__ == "__main__":
    unittest.main()
