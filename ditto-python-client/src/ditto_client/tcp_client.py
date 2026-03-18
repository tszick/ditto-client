"""
DittoTcpClient – connects directly to dittod TCP port 7777.

Uses the bincode 1.x binary protocol over a persistent TCP connection.
Requests are serialised via a threading.Lock; safe to call from multiple threads.
"""

from __future__ import annotations

import socket
import struct
import threading

from .bincode import (
    ClientResponse,
    decode_response,
    encode_delete,
    encode_get,
    encode_ping,
    encode_set,
)
from .types import DittoError, DittoGetResult, DittoSetResult


class DittoTcpClient:
    """
    Synchronous TCP client for dittod (port 7777).

    Usage::

        client = DittoTcpClient(host='localhost', port=7777)
        client.connect()
        client.set('key', 'value', ttl_secs=60)
        result = client.get('key')
        client.close()

    Also usable as a context manager::

        with DittoTcpClient() as client:
            client.set('k', 'v')
    """

    def __init__(self, host: str = "localhost", port: int = 7777, auth_token: str | None = None) -> None:
        self._host = host
        self._port = port
        self._auth_token = auth_token
        self._sock: socket.socket | None = None
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Context manager
    # ------------------------------------------------------------------

    def __enter__(self) -> DittoTcpClient:
        self.connect()
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    # ------------------------------------------------------------------
    # Connection lifecycle
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """Open the TCP connection. Must be called before any other method."""
        if self._sock is not None:
            return
        sock = socket.create_connection((self._host, self._port))
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._sock = sock

        if self._auth_token is not None:
            from .bincode import encode_auth
            resp = self._send(encode_auth(self._auth_token))
            if getattr(resp, "type", None) == "Error":
                self.close()
                raise DittoError(getattr(resp, "code", "Error"), getattr(resp, "message", str(resp)))
            if getattr(resp, "type", None) != "AuthOk":
                self.close()
                raise RuntimeError(f"Unexpected auth response: {getattr(resp, 'type', type(resp))}")

    def close(self) -> None:
        """Gracefully close the TCP connection."""
        with self._lock:
            if self._sock is None:
                return
            try:
                self._sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self._sock.close()
            self._sock = None

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def ping(self) -> bool:
        """Send a Ping. Returns True when Pong is received."""
        resp = self._send(encode_ping())
        return resp.type == "Pong"

    def get(self, key: str) -> DittoGetResult | None:
        """
        Get a key.  Returns None when the key does not exist or has expired.
        The returned ``value`` is the raw bytes stored for the key.
        """
        resp = self._send(encode_get(key))
        if resp.type == "NotFound":
            return None
        if resp.type == "Value":
            return DittoGetResult(value=resp.value, version=resp.version)  # type: ignore[union-attr]
        if resp.type == "Error":
            raise DittoError(resp.code, str(resp))  # type: ignore[union-attr]
        raise RuntimeError(f"Unexpected response: {resp.type}")

    def set(
        self,
        key: str,
        value: str | bytes,
        ttl_secs: int = 0,
    ) -> DittoSetResult:
        """
        Set a key.  ``value`` may be a str (UTF-8 encoded) or bytes.
        ``ttl_secs=0`` means no expiry.
        """
        raw = value.encode("utf-8") if isinstance(value, str) else value
        resp = self._send(encode_set(key, raw, ttl_secs))
        if resp.type == "Ok":
            return DittoSetResult(version=resp.version)  # type: ignore[union-attr]
        if resp.type == "Error":
            raise DittoError(resp.code, str(resp))  # type: ignore[union-attr]
        raise RuntimeError(f"Unexpected response: {resp.type}")

    def delete(self, key: str) -> bool:
        """Delete a key. Returns True if the key existed, False if not found."""
        resp = self._send(encode_delete(key))
        if resp.type == "Deleted":
            return True
        if resp.type == "NotFound":
            return False
        if resp.type == "Error":
            raise DittoError(resp.code, str(resp))  # type: ignore[union-attr]
        raise RuntimeError(f"Unexpected response: {resp.type}")

    # ------------------------------------------------------------------
    # Internal send / receive
    # ------------------------------------------------------------------

    def _send(self, frame: bytes) -> ClientResponse:
        with self._lock:
            if self._sock is None:
                raise RuntimeError("Not connected. Call connect() first.")
            self._sock.sendall(frame)
            return self._recv()

    def _recv(self) -> ClientResponse:
        """Read exactly one framed response from the socket."""
        # 4-byte big-endian length prefix
        header = self._recvn(4)
        payload_len = struct.unpack(">I", header)[0]
        payload = self._recvn(payload_len)
        return decode_response(payload)

    def _recvn(self, n: int) -> bytes:
        """Read exactly n bytes, blocking until they arrive."""
        assert self._sock is not None
        data = bytearray()
        while len(data) < n:
            chunk = self._sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("Connection closed by server.")
            data.extend(chunk)
        return bytes(data)
