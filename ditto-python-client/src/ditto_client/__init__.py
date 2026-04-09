"""Ditto Python client library."""

from .http_client import DittoHttpClient
from .tcp_client import DittoTcpClient
from .types import (
    DittoError,
    DittoErrorCode,
    DittoDeleteByPatternResult,
    DittoGetResult,
    DittoSetResult,
    DittoSetTtlByPatternResult,
    DittoStatsResult,
    DittoWatchEvent,
)

__all__ = [
    "DittoTcpClient",
    "DittoHttpClient",
    "DittoError",
    "DittoErrorCode",
    "DittoDeleteByPatternResult",
    "DittoGetResult",
    "DittoSetResult",
    "DittoSetTtlByPatternResult",
    "DittoStatsResult",
    "DittoWatchEvent",
]
