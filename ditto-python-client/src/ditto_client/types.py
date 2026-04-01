"""Shared types for the Ditto client library."""

from __future__ import annotations

import dataclasses
from enum import Enum


@dataclasses.dataclass(frozen=True)
class DittoGetResult:
    """Returned by get(). ``value`` is the raw bytes stored for the key."""
    value:   bytes
    version: int


@dataclasses.dataclass(frozen=True)
class DittoSetResult:
    """Returned by set()."""
    version: int


@dataclasses.dataclass(frozen=True)
class DittoDeleteByPatternResult:
    """Returned by delete_by_pattern()."""
    deleted: int


@dataclasses.dataclass(frozen=True)
class DittoSetTtlByPatternResult:
    """Returned by set_ttl_by_pattern()."""
    updated: int


@dataclasses.dataclass
class DittoStatsResult:
    """Returned by stats() (HTTP client only)."""
    node_id:                     str   = ""
    status:                      str   = ""
    is_primary:                  bool  = False
    committed_index:             int   = 0
    key_count:                   int   = 0
    memory_used_bytes:           int   = 0
    memory_max_bytes:            int   = 0
    evictions:                   int   = 0
    hit_count:                   int   = 0
    miss_count:                  int   = 0
    uptime_secs:                 int   = 0
    value_size_limit_bytes:      int   = 0
    max_keys_limit:              int   = 0
    compression_enabled:         bool  = False
    compression_threshold_bytes: int   = 0
    node_name:                   str   = ""
    backup_dir_bytes:            int   = 0


class DittoErrorCode(str, Enum):
    NODE_INACTIVE    = "NodeInactive"
    NO_QUORUM        = "NoQuorum"
    KEY_NOT_FOUND    = "KeyNotFound"
    INTERNAL_ERROR   = "InternalError"
    WRITE_TIMEOUT    = "WriteTimeout"
    VALUE_TOO_LARGE  = "ValueTooLarge"
    KEY_LIMIT_REACHED = "KeyLimitReached"
    AUTH_FAILED      = "AuthFailed"


class DittoError(Exception):
    """Raised when the server returns an error response."""

    def __init__(self, code: DittoErrorCode | str, message: str) -> None:
        super().__init__(message)
        self.code: DittoErrorCode | str = code
