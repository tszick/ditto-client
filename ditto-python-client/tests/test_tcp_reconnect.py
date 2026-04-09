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


class TcpReconnectTests(unittest.TestCase):
    def test_auto_reconnect_ping(self) -> None:
        cases = [
            ("disabled", False, False),
            ("enabled", True, True),
        ]

        for _, auto_reconnect, want_ok in cases:
            with self.subTest(auto_reconnect=auto_reconnect):
                server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                server.bind(("127.0.0.1", 0))
                server.listen(2)
                server.settimeout(2.0)
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
                    payload_len = struct.unpack(">I", recv_exact(conn, 4))[0]
                    payload = recv_exact(conn, payload_len)
                    return struct.unpack_from("<I", payload, 0)[0]

                def mock_server() -> None:
                    try:
                        conn1, _ = server.accept()
                        try:
                            self.assertEqual(3, recv_variant(conn1))  # ping
                        finally:
                            conn1.close()

                        server.settimeout(0.5)
                        try:
                            conn2, _ = server.accept()
                        except socket.timeout:
                            if auto_reconnect:
                                raise
                            return

                        with conn2:
                            self.assertEqual(3, recv_variant(conn2))  # retried ping
                            conn2.sendall(frame(struct.pack("<I", 4)))  # pong
                    except Exception as exc:  # pragma: no cover
                        errors.append(exc)
                    finally:
                        server.close()

                th = threading.Thread(target=mock_server, daemon=True)
                th.start()

                with DittoTcpClient(
                    host=host,
                    port=port,
                    connect_timeout_secs=2.0,
                    socket_timeout_secs=2.0,
                    auto_reconnect=auto_reconnect,
                ) as client:
                    if want_ok:
                        self.assertTrue(client.ping())
                    else:
                        with self.assertRaises(Exception):
                            client.ping()

                th.join(timeout=2.0)
                self.assertFalse(th.is_alive(), "mock server thread did not finish")
                if errors:
                    raise errors[0]


if __name__ == "__main__":
    unittest.main()
