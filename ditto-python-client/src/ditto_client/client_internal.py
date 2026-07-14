from __future__ import annotations

import json

from .types import DittoError, DittoErrorCode

_ATOMIC_UNSUPPORTED_STATUSES = (400, 404, 501)


def namespace_headers(namespace: str | None) -> dict[str, str] | None:
    if namespace is None:
        return None
    normalized = namespace.strip()
    if normalized == "":
        return None
    return {"X-Ditto-Namespace": normalized}


def raise_if_atomic_http_unsupported(status: int, body: str, operation: str) -> None:
    if status not in _ATOMIC_UNSUPPORTED_STATUSES:
        return
    raise atomic_http_unsupported_error(body, operation)


def atomic_http_unsupported_error(body: str, operation: str) -> DittoError:
    try:
        data = json.loads(body)
        if isinstance(data, dict) and data.get("error") == "UnsupportedRequest":
            return DittoError(
                DittoErrorCode.UNSUPPORTED_REQUEST,
                data.get("message") or "UnsupportedRequest",
            )
    except (ValueError, AttributeError):
        pass
    return unsupported_atomic_operation_error(operation)


def normalize_atomic_tcp_error(error: Exception, operation: str) -> Exception:
    if isinstance(error, DittoError):
        return error
    normalized = str(error).lower()
    if (
        "unsupported" in normalized
        or "protocol" in normalized
        or "decode" in normalized
        or "unexpected response" in normalized
        or "eof" in normalized
        or "connection reset" in normalized
    ):
        return unsupported_atomic_operation_error(operation)
    return error


def unsupported_atomic_operation_error(operation: str) -> DittoError:
    return DittoError(
        DittoErrorCode.UNSUPPORTED_REQUEST,
        f"UnsupportedRequest: server does not support {operation}. "
        "Upgrade dittod to a version with atomic primitives.",
    )
