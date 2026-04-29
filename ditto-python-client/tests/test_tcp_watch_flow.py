from __future__ import annotations

import socket
import struct
import sys
import threading
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ditto_client import DittoTcpClient
from ditto_client.wire import (
    REQUEST_FIELDS,
    RESPONSE_FIELDS,
    decode_client_request_variant,
    encode_version_response_inner,
    encode_watch_event_inner,
    frame_client_response,
)


class TcpWatchFlowTests(unittest.TestCase):
    def test_watch_set_event_unwatch_flow(self) -> None:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        host, port = server.getsockname()
        errors: list[Exception] = []

        def recv_exact(conn: socket.socket, n: int) -> bytes:
            buf = bytearray()
            while len(buf) < n:
                chunk = conn.recv(n - len(buf))
                if not chunk:
                    raise ConnectionError("connection closed")
                buf.extend(chunk)
            return bytes(buf)

        def recv_variant(conn: socket.socket) -> int:
            head = recv_exact(conn, 4)
            payload_len = struct.unpack(">I", head)[0]
            payload = recv_exact(conn, payload_len)
            field, _ = decode_client_request_variant(payload)
            return field

        def mock_server() -> None:
            conn, _ = server.accept()
            try:
                self.assertEqual(REQUEST_FIELDS["WATCH"], recv_variant(conn))
                conn.sendall(frame_client_response(RESPONSE_FIELDS["WATCHING"], b""))

                self.assertEqual(REQUEST_FIELDS["SET"], recv_variant(conn))
                conn.sendall(frame_client_response(RESPONSE_FIELDS["OK"], encode_version_response_inner(1)))
                conn.sendall(frame_client_response(
                    RESPONSE_FIELDS["WATCH_EVENT"],
                    encode_watch_event_inner("k", b"value", 2),
                ))

                self.assertEqual(REQUEST_FIELDS["UNWATCH"], recv_variant(conn))
                conn.sendall(frame_client_response(RESPONSE_FIELDS["UNWATCHED"], b""))
            except Exception as exc:  # pragma: no cover - surfaced via thread join check
                errors.append(exc)
            finally:
                conn.close()
                server.close()

        th = threading.Thread(target=mock_server, daemon=True)
        th.start()

        with DittoTcpClient(host=host, port=port, connect_timeout_secs=2.0, socket_timeout_secs=2.0) as client:
            client.watch("k")
            set_res = client.set("k", "value")
            self.assertEqual(1, set_res.version)
            event = client.wait_watch_event()
            self.assertEqual("k", event.key)
            self.assertEqual(b"value", event.value)
            self.assertEqual(2, event.version)
            client.unwatch("k")

        th.join(timeout=2.0)
        self.assertFalse(th.is_alive(), "mock server thread did not finish")
        if errors:
            raise errors[0]


if __name__ == "__main__":
    unittest.main()
