"""Ditto Python client library."""

from .http_client import DittoHttpClient
from .tcp_client import DittoTcpClient
from .types import (
    DittoCounterResult,
    DittoError,
    DittoErrorCode,
    DittoDeleteByPatternResult,
    DittoGetResult,
    DittoSetNxResult,
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
    "DittoCounterResult",
    "DittoDeleteByPatternResult",
    "DittoGetResult",
    "DittoSetNxResult",
    "DittoSetResult",
    "DittoSetTtlByPatternResult",
    "DittoStatsResult",
    "DittoWatchEvent",
]
