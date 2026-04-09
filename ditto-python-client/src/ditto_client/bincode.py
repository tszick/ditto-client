"""
Minimal bincode 1.x encoder / decoder for the Ditto TCP wire protocol.

Bincode 1.x default rules:
  - integers:     little-endian
  - enum variant: u32 LE (declaration-order index)
  - String/Bytes: u64 LE length prefix + raw bytes
  - Option<T>:    u8 (0 = None, 1 = Some) + T if Some
  - bool:         u8

Wire framing (added by tcp_server.rs):
  - 4-byte big-endian payload-length prefix before every message

ClientRequest variant indices: Get=0, Set=1, Delete=2, Ping=3, Auth=4,
                               Watch=5, Unwatch=6, DeleteByPattern=7, SetTtlByPattern=8
ClientResponse variant indices: Value=0, Ok=1, Deleted=2, NotFound=3, Pong=4, AuthOk=5,
                                Error=6, Watching=7, Unwatched=8, WatchEvent=9,
                                PatternDeleted=10, PatternTtlUpdated=11
ErrorCode variant indices: NodeInactive=0, NoQuorum=1, KeyNotFound=2,
                            InternalError=3, WriteTimeout=4, ValueTooLarge=5,
                            KeyLimitReached=6
"""

from __future__ import annotations

import struct
from typing import NamedTuple

from .types import DittoErrorCode

# ---------------------------------------------------------------------------
# ClientRequest encoding
# ---------------------------------------------------------------------------

_ERROR_CODE_NAMES = [
    DittoErrorCode.NODE_INACTIVE,
    DittoErrorCode.NO_QUORUM,
    DittoErrorCode.KEY_NOT_FOUND,
    DittoErrorCode.INTERNAL_ERROR,
    DittoErrorCode.WRITE_TIMEOUT,
    DittoErrorCode.VALUE_TOO_LARGE,
    DittoErrorCode.KEY_LIMIT_REACHED,
    DittoErrorCode.AUTH_FAILED,
]


def _pack_string(s: str | bytes) -> bytes:
    """Encode a string as u64-LE length + raw bytes."""
    raw = s.encode("utf-8") if isinstance(s, str) else s
    return struct.pack("<Q", len(raw)) + raw


def _frame(payload: bytes) -> bytes:
    """Prepend 4-byte big-endian payload-length prefix."""
    return struct.pack(">I", len(payload)) + payload

def _pack_opt_string(s: str | None) -> bytes:
    if s is None or s.strip() == "":
        return struct.pack("B", 0)
    return struct.pack("B", 1) + _pack_string(s)


def encode_get(key: str, namespace: str | None = None) -> bytes:
    return _frame(struct.pack("<I", 0) + _pack_string(key) + _pack_opt_string(namespace))


def encode_set(key: str, value: bytes, ttl_secs: int = 0, namespace: str | None = None) -> bytes:
    has_ttl = ttl_secs > 0
    payload = (
        struct.pack("<I", 1)
        + _pack_string(key)
        + _pack_string(value)
        + struct.pack("B", 1 if has_ttl else 0)
        + (struct.pack("<Q", ttl_secs) if has_ttl else b"")
        + _pack_opt_string(namespace)
    )
    return _frame(payload)


def encode_delete(key: str, namespace: str | None = None) -> bytes:
    return _frame(struct.pack("<I", 2) + _pack_string(key) + _pack_opt_string(namespace))


def encode_ping() -> bytes:
    return _frame(struct.pack("<I", 3))


def encode_auth(token: str) -> bytes:
    return _frame(struct.pack("<I", 4) + _pack_string(token))

def encode_watch(key: str, namespace: str | None = None) -> bytes:
    return _frame(struct.pack("<I", 5) + _pack_string(key) + _pack_opt_string(namespace))


def encode_unwatch(key: str, namespace: str | None = None) -> bytes:
    return _frame(struct.pack("<I", 6) + _pack_string(key) + _pack_opt_string(namespace))


def encode_delete_by_pattern(pattern: str, namespace: str | None = None) -> bytes:
    return _frame(struct.pack("<I", 7) + _pack_string(pattern) + _pack_opt_string(namespace))


def encode_set_ttl_by_pattern(pattern: str, ttl_secs: int = 0, namespace: str | None = None) -> bytes:
    has_ttl = ttl_secs > 0
    payload = (
        struct.pack("<I", 8)
        + _pack_string(pattern)
        + struct.pack("B", 1 if has_ttl else 0)
        + (struct.pack("<Q", ttl_secs) if has_ttl else b"")
        + _pack_opt_string(namespace)
    )
    return _frame(payload)


# ---------------------------------------------------------------------------
# ClientResponse decoding
# ---------------------------------------------------------------------------

class _Value(NamedTuple):
    type: str          # 'Value'
    key: str
    value: bytes
    version: int

class _Ok(NamedTuple):
    type: str          # 'Ok'
    version: int

class _Simple(NamedTuple):
    type: str          # 'Deleted' | 'NotFound' | 'Pong' | 'AuthOk' | 'Watching' | 'Unwatched'

class _Error(NamedTuple):
    type: str          # 'Error'
    code: DittoErrorCode
    message: str

class _WatchEvent(NamedTuple):
    type: str          # 'WatchEvent'
    key: str
    value: bytes | None
    version: int

class _PatternDeleted(NamedTuple):
    type: str          # 'PatternDeleted'
    deleted: int

class _PatternTtlUpdated(NamedTuple):
    type: str          # 'PatternTtlUpdated'
    updated: int

ClientResponse = _Value | _Ok | _Simple | _Error | _WatchEvent | _PatternDeleted | _PatternTtlUpdated


def decode_response(buf: bytes) -> ClientResponse:
    off = 0

    def read_u32() -> int:
        nonlocal off
        v = struct.unpack_from("<I", buf, off)[0]
        off += 4
        return v

    def read_u64() -> int:
        nonlocal off
        v = struct.unpack_from("<Q", buf, off)[0]
        off += 8
        return v

    def read_bytes() -> bytes:
        length = read_u64()
        nonlocal off
        data = buf[off:off + length]
        off += length
        return data

    variant = read_u32()

    if variant == 0:   # Value { key, value, version }
        key     = read_bytes().decode("utf-8")
        value   = read_bytes()
        version = read_u64()
        return _Value("Value", key, value, version)

    if variant == 1:   # Ok { version }
        version = read_u64()
        return _Ok("Ok", version)

    if variant == 2:   # Deleted
        return _Simple("Deleted")

    if variant == 3:   # NotFound
        return _Simple("NotFound")

    if variant == 4:   # Pong
        return _Simple("Pong")

    if variant == 5:   # AuthOk
        return _Simple("AuthOk")

    if variant == 6:   # Error { code, message }
        code_idx = read_u32()
        message  = read_bytes().decode("utf-8")
        code = _ERROR_CODE_NAMES[code_idx] if code_idx < len(_ERROR_CODE_NAMES) else DittoErrorCode.INTERNAL_ERROR
        return _Error("Error", code, message)

    if variant == 7:   # Watching
        return _Simple("Watching")

    if variant == 8:   # Unwatched
        return _Simple("Unwatched")

    if variant == 9:   # WatchEvent { key, value: Option<Bytes>, version }
        key = read_bytes().decode("utf-8")
        has_value = struct.unpack_from("B", buf, off)[0]
        off += 1
        value: bytes | None = None
        if has_value == 1:
            value = read_bytes()
        version = read_u64()
        return _WatchEvent("WatchEvent", key, value, version)

    if variant == 10:  # PatternDeleted { deleted }
        deleted = read_u64()
        return _PatternDeleted("PatternDeleted", deleted)

    if variant == 11:  # PatternTtlUpdated { updated }
        updated = read_u64()
        return _PatternTtlUpdated("PatternTtlUpdated", updated)

    raise ValueError(f"Unknown ClientResponse variant: {variant}")
