"""
DittoTcpClient – connects directly to dittod TCP port 7777.

Uses the bincode 1.x binary protocol over a persistent TCP connection.
Requests are serialised via a threading.Lock; safe to call from multiple threads.
"""

from __future__ import annotations

import socket
import struct
import threading
import errno

from .bincode import (
    ClientResponse,
    decode_response,
    encode_delete,
    encode_delete_by_pattern,
    encode_get,
    encode_ping,
    encode_set,
    encode_set_ttl_by_pattern,
    encode_unwatch,
    encode_watch,
)
from .types import (
    DittoDeleteByPatternResult,
    DittoError,
    DittoGetResult,
    DittoSetResult,
    DittoSetTtlByPatternResult,
    DittoWatchEvent,
)
from .validation import validate_core_inputs, validate_pattern_inputs


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

    def __init__(
        self,
        host: str = "localhost",
        port: int = 7777,
        auth_token: str | None = None,
        *,
        connect_timeout_secs: float = 10.0,
        socket_timeout_secs: float = 10.0,
        max_frame_bytes: int = 8 * 1024 * 1024,
        strict_mode: bool = False,
        auto_reconnect: bool = False,
    ) -> None:
        self._host = host
        self._port = port
        self._auth_token = auth_token
        self._connect_timeout_secs = connect_timeout_secs
        self._socket_timeout_secs = socket_timeout_secs
        self._max_frame_bytes = max_frame_bytes
        self._strict_mode = strict_mode
        self._auto_reconnect = auto_reconnect
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
        with self._lock:
            self._connect_locked()

    def close(self) -> None:
        """Gracefully close the TCP connection."""
        with self._lock:
            self._close_locked()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def ping(self) -> bool:
        """Send a Ping. Returns True when Pong is received."""
        resp = self._send(encode_ping())
        return resp.type == "Pong"

    def get(self, key: str, namespace: str | None = None) -> DittoGetResult | None:
        """
        Get a key.  Returns None when the key does not exist or has expired.
        The returned ``value`` is the raw bytes stored for the key.
        """
        validate_core_inputs(self._strict_mode, "get", key, namespace)
        resp = self._send(encode_get(key, namespace))
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
        namespace: str | None = None,
    ) -> DittoSetResult:
        """
        Set a key.  ``value`` may be a str (UTF-8 encoded) or bytes.
        ``ttl_secs=0`` means no expiry.
        """
        validate_core_inputs(self._strict_mode, "set", key, namespace)
        raw = value.encode("utf-8") if isinstance(value, str) else value
        resp = self._send(encode_set(key, raw, ttl_secs, namespace))
        if resp.type == "Ok":
            return DittoSetResult(version=resp.version)  # type: ignore[union-attr]
        if resp.type == "Error":
            raise DittoError(resp.code, str(resp))  # type: ignore[union-attr]
        raise RuntimeError(f"Unexpected response: {resp.type}")

    def delete(self, key: str, namespace: str | None = None) -> bool:
        """Delete a key. Returns True if the key existed, False if not found."""
        validate_core_inputs(self._strict_mode, "delete", key, namespace)
        resp = self._send(encode_delete(key, namespace))
        if resp.type == "Deleted":
            return True
        if resp.type == "NotFound":
            return False
        if resp.type == "Error":
            raise DittoError(resp.code, str(resp))  # type: ignore[union-attr]
        raise RuntimeError(f"Unexpected response: {resp.type}")

    def delete_by_pattern(self, pattern: str, namespace: str | None = None) -> DittoDeleteByPatternResult:
        """Delete all keys matching a glob-style pattern ('*' wildcard)."""
        validate_pattern_inputs(self._strict_mode, "delete_by_pattern", pattern, namespace)
        resp = self._send(encode_delete_by_pattern(pattern, namespace))
        if resp.type == "PatternDeleted":
            return DittoDeleteByPatternResult(deleted=resp.deleted)  # type: ignore[union-attr]
        if resp.type == "Error":
            raise DittoError(resp.code, str(resp))  # type: ignore[union-attr]
        raise RuntimeError(f"Unexpected response: {resp.type}")

    def set_ttl_by_pattern(
        self,
        pattern: str,
        ttl_secs: int = 0,
        namespace: str | None = None,
    ) -> DittoSetTtlByPatternResult:
        """
        Update TTL for all keys matching a glob-style pattern ('*' wildcard).
        ``ttl_secs <= 0`` removes TTL from matched keys.
        """
        validate_pattern_inputs(self._strict_mode, "set_ttl_by_pattern", pattern, namespace)
        resp = self._send(encode_set_ttl_by_pattern(pattern, ttl_secs, namespace))
        if resp.type == "PatternTtlUpdated":
            return DittoSetTtlByPatternResult(updated=resp.updated)  # type: ignore[union-attr]
        if resp.type == "Error":
            raise DittoError(resp.code, str(resp))  # type: ignore[union-attr]
        raise RuntimeError(f"Unexpected response: {resp.type}")

    def watch(self, key: str, namespace: str | None = None) -> None:
        """Subscribe to updates for a key."""
        validate_core_inputs(self._strict_mode, "watch", key, namespace)
        resp = self._send(encode_watch(key, namespace))
        if resp.type == "Watching":
            return
        if resp.type == "Error":
            raise DittoError(resp.code, str(resp))  # type: ignore[union-attr]
        raise RuntimeError(f"Unexpected response: {resp.type}")

    def unwatch(self, key: str, namespace: str | None = None) -> None:
        """Cancel a key subscription."""
        validate_core_inputs(self._strict_mode, "unwatch", key, namespace)
        resp = self._send(encode_unwatch(key, namespace))
        if resp.type == "Unwatched":
            return
        if resp.type == "Error":
            raise DittoError(resp.code, str(resp))  # type: ignore[union-attr]
        raise RuntimeError(f"Unexpected response: {resp.type}")

    def wait_watch_event(self) -> DittoWatchEvent:
        """Block until the next watch event frame arrives."""
        with self._lock:
            if self._sock is None:
                raise RuntimeError("Not connected. Call connect() first.")
            resp = self._recv()
        if resp.type == "WatchEvent":
            return DittoWatchEvent(key=resp.key, value=resp.value, version=resp.version)  # type: ignore[union-attr]
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
            try:
                self._sock.sendall(frame)
                return self._recv()
            except (OSError, ConnectionError):
                self._close_locked()
                if not self._auto_reconnect:
                    raise
                self._connect_locked()
                assert self._sock is not None
                self._sock.sendall(frame)
                return self._recv()

    def _recv(self) -> ClientResponse:
        """Read exactly one framed response from the socket."""
        # 4-byte big-endian length prefix
        header = self._recvn(4)
        payload_len = struct.unpack(">I", header)[0]
        if payload_len > self._max_frame_bytes:
            raise ConnectionError(
                f"Incoming frame too large ({payload_len} bytes > limit {self._max_frame_bytes} bytes)"
            )
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

    def _close_locked(self) -> None:
        if self._sock is None:
            return
        try:
            self._sock.shutdown(socket.SHUT_RDWR)
        except OSError as exc:
            # It's normal to hit these when the peer already closed or the
            # socket is no longer connected while we are shutting down.
            if exc.errno not in (errno.ENOTCONN, errno.EBADF, errno.EINVAL):
                raise
        self._sock.close()
        self._sock = None

    def _connect_locked(self) -> None:
        if self._sock is not None:
            return
        sock = socket.create_connection((self._host, self._port), timeout=self._connect_timeout_secs)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.settimeout(self._socket_timeout_secs)
        self._sock = sock

        if self._auth_token is not None:
            from .bincode import encode_auth
            self._sock.sendall(encode_auth(self._auth_token))
            resp = self._recv()
            if getattr(resp, "type", None) == "Error":
                self._close_locked()
                raise DittoError(getattr(resp, "code", "Error"), getattr(resp, "message", str(resp)))
            if getattr(resp, "type", None) != "AuthOk":
                self._close_locked()
                raise RuntimeError(f"Unexpected auth response: {getattr(resp, 'type', type(resp))}")
