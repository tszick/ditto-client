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
    frame_client_response,
)


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
                    field, _ = decode_client_request_variant(payload)
                    return field

                def mock_server() -> None:
                    try:
                        conn1, _ = server.accept()
                        try:
                            self.assertEqual(REQUEST_FIELDS["PING"], recv_variant(conn1))
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
                            self.assertEqual(REQUEST_FIELDS["PING"], recv_variant(conn2))
                            conn2.sendall(frame_client_response(RESPONSE_FIELDS["PONG"], b""))
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


    def test_auto_reconnect_does_not_retry_mutation(self) -> None:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.bind(("127.0.0.1", 0))
        server.listen(2)
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

        def mock_server() -> None:
            try:
                conn1, _ = server.accept()
                with conn1:
                    payload_len = struct.unpack(">I", recv_exact(conn1, 4))[0]
                    payload = recv_exact(conn1, payload_len)
                    field, _ = decode_client_request_variant(payload)
                    self.assertEqual(REQUEST_FIELDS["INCR"], field)

                server.settimeout(0.5)
                try:
                    conn2, _ = server.accept()
                except socket.timeout:
                    return
                conn2.close()
                errors.append(AssertionError("mutation was retried on a second connection"))
            except Exception as exc:  # pragma: no cover
                errors.append(exc)
            finally:
                server.close()

        thread = threading.Thread(target=mock_server, daemon=True)
        thread.start()
        with DittoTcpClient(host=host, port=port, auto_reconnect=True) as client:
            with self.assertRaisesRegex(ConnectionError, "outcome unknown"):
                client.incr("counter", 1)

        thread.join(timeout=2.0)
        self.assertFalse(thread.is_alive(), "mock server thread did not finish")
if __name__ == "__main__":
    unittest.main()
