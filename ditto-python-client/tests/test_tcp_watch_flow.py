from __future__ import annotations

import socket
import struct
import sys
import threading
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ditto_client import DittoTcpClient


def frame(payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + payload


def u64(v: int) -> bytes:
    return struct.pack("<Q", v)


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
            return struct.unpack_from("<I", payload, 0)[0]

        def send_simple(conn: socket.socket, variant: int) -> None:
            conn.sendall(frame(struct.pack("<I", variant)))

        def send_ok(conn: socket.socket, version: int) -> None:
            conn.sendall(frame(struct.pack("<IQ", 1, version)))

        def send_watch_event(conn: socket.socket, key: str, value: bytes, version: int) -> None:
            payload = (
                struct.pack("<I", 9)
                + u64(len(key.encode("utf-8")))
                + key.encode("utf-8")
                + struct.pack("B", 1)
                + u64(len(value))
                + value
                + u64(version)
            )
            conn.sendall(frame(payload))

        def mock_server() -> None:
            conn, _ = server.accept()
            try:
                self.assertEqual(5, recv_variant(conn))
                send_simple(conn, 7)
                self.assertEqual(1, recv_variant(conn))
                send_ok(conn, 1)
                send_watch_event(conn, "k", b"value", 2)
                self.assertEqual(6, recv_variant(conn))
                send_simple(conn, 8)
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
