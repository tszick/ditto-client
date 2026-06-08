# Generated from api/ditto-http-api.yaml v1.2.0
# DO NOT EDIT MANUALLY — regenerate with: cd src/tools && npm run generate

from __future__ import annotations

import dataclasses
import base64
import json

from .http_client_base import DittoHttpClientBase
from .types import (
    DittoCounterResult,
    DittoDeleteByPatternResult,
    DittoError,
    DittoErrorCode,
    DittoGetResult,
    DittoSetNxResult,
    DittoSetResult,
    DittoSetTtlByPatternResult,
    DittoStatsResult,
)
from .validation import validate_core_inputs, validate_pattern_inputs


# Statuses an OLD dittod (no atomic-primitive routes) returns for SET_NX/INCR:
# 400 (rejects the request shape), 404 (route missing), 501 (explicitly
# unsupported). TypeMismatch/Overflow are 409 and flow through _assert_ok.
_ATOMIC_UNSUPPORTED_STATUSES = (400, 404, 501)


class DittoHttpClient(DittoHttpClientBase):
    """HTTP client for the Ditto cache server (port 7778)."""

    @staticmethod
    def _namespace_headers(namespace: str | None) -> dict[str, str] | None:
        if namespace is None or namespace.strip() == "":
            return None
        return {"X-Ditto-Namespace": namespace}

    # ── Generated endpoint methods (from api/ditto-http-api.yaml) ──────────

    def ping(self) -> bool:
        """Check whether the node is alive and accepting requests."""
        status, body = self._request('/ping')
        if status != 200:
            return False
        data = json.loads(body)
        return data.get('pong') is True

    def get(self, key: str, namespace: str | None = None) -> DittoGetResult | None:
        """Get a value by key. Returns null when the key does not exist or has expired."""
        validate_core_inputs(self._strict_mode, "get", key, namespace)
        status, body = self._request(
            f'/key/{self._url_encode(key)}',
            extra_headers=self._namespace_headers(namespace),
        )
        if status == 404:
            return None
        self._assert_ok(status, body)
        data = json.loads(body)
        value = (
            base64.b64decode(data["value_base64"])
            if data.get("value_base64")
            else data["value"].encode("utf-8")
        )
        return DittoGetResult(value=value, version=data['version'])

    def set(self, key: str, value: str, ttl_secs: int = 0, namespace: str | None = None) -> DittoSetResult:
        """Set a value. ttlSecs = 0 or omitted means no expiry."""
        validate_core_inputs(self._strict_mode, "set", key, namespace)
        path = f'/key/{self._url_encode(key)}'
        if ttl_secs > 0:
            path += f'?ttl={ttl_secs}'
        status, body = self._request(
            path,
            method='PUT',
            body=value.encode('utf-8'),
            content_type='text/plain',
            extra_headers=self._namespace_headers(namespace),
        )
        self._assert_ok(status, body)
        data = json.loads(body)
        return DittoSetResult(version=data['version'])

    def set_nx(
        self,
        key: str,
        value: str | bytes,
        ttl_secs: int = 0,
        namespace: str | None = None,
    ) -> DittoSetNxResult:
        """Atomic create-if-absent. ``created`` is False (with the existing
        version) when the key already exists — no write is performed."""
        validate_core_inputs(self._strict_mode, "set", key, namespace)
        path = f'/key/{self._url_encode(key)}?nx=1'
        if ttl_secs > 0:
            path += f'&ttl={ttl_secs}'
        raw = value.encode('utf-8') if isinstance(value, str) else value
        status, body = self._request(
            path,
            method='POST',
            body=raw,
            content_type='application/octet-stream',
            extra_headers=self._namespace_headers(namespace),
        )
        self._raise_if_atomic_unsupported(status, body, 'SET_NX')
        self._assert_ok(status, body)
        data = json.loads(body)
        return DittoSetNxResult(created=bool(data['created']), version=int(data['version']))

    def incr(
        self,
        key: str,
        delta: int = 1,
        ttl_secs_on_create: int = 0,
        namespace: str | None = None,
    ) -> DittoCounterResult:
        """Atomic counter increment. Creates the key at ``delta`` if absent
        (with ``ttl_secs_on_create``); never resets the TTL of an existing key."""
        validate_core_inputs(self._strict_mode, "set", key, namespace)
        # Send delta as a JSON string so the int64 survives any consumer that
        # would coerce a large number to a float (server accepts string or number).
        payload_obj: dict[str, object] = {"delta": str(delta)}
        if ttl_secs_on_create > 0:
            payload_obj["ttl_secs_on_create"] = ttl_secs_on_create
        payload = json.dumps(payload_obj).encode("utf-8")
        status, body = self._request(
            f'/key/{self._url_encode(key)}/incr',
            method='POST',
            body=payload,
            content_type='application/json',
            extra_headers=self._namespace_headers(namespace),
        )
        self._raise_if_atomic_unsupported(status, body, 'INCR')
        self._assert_ok(status, body)
        data = json.loads(body)
        return DittoCounterResult(value=int(data['value']), version=int(data['version']))

    @staticmethod
    def _raise_if_atomic_unsupported(status: int, body: str, operation: str) -> None:
        if status not in _ATOMIC_UNSUPPORTED_STATUSES:
            return
        try:
            data = json.loads(body)
            if isinstance(data, dict) and data.get("error") == "UnsupportedRequest":
                raise DittoError(
                    DittoErrorCode.UNSUPPORTED_REQUEST,
                    data.get("message") or "UnsupportedRequest",
                )
        except (ValueError, AttributeError):
            pass  # non-JSON body — fall through to the normalized message
        raise DittoError(
            DittoErrorCode.UNSUPPORTED_REQUEST,
            f"UnsupportedRequest: server does not support {operation}. "
            "Upgrade dittod to a version with atomic primitives.",
        )

    def delete(self, key: str, namespace: str | None = None) -> bool:
        """Delete a key. Returns true if the key existed, false if not found."""
        validate_core_inputs(self._strict_mode, "delete", key, namespace)
        status, body = self._request(
            f'/key/{self._url_encode(key)}',
            method='DELETE',
            extra_headers=self._namespace_headers(namespace),
        )
        if status == 404:
            return False
        if status == 204:
            return True
        self._assert_ok(status, body)
        return True

    def delete_by_pattern(self, pattern: str, namespace: str | None = None) -> DittoDeleteByPatternResult:
        """Delete all keys matching a glob-style pattern ('*' wildcard)."""
        validate_pattern_inputs(self._strict_mode, "delete_by_pattern", pattern, namespace)
        payload = json.dumps({"pattern": pattern}).encode("utf-8")
        status, body = self._request(
            "/keys/delete-by-pattern",
            method="POST",
            body=payload,
            content_type="application/json",
            extra_headers=self._namespace_headers(namespace),
        )
        self._assert_ok(status, body)
        data = json.loads(body)
        return DittoDeleteByPatternResult(deleted=data["deleted"])

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
        payload_obj = {"pattern": pattern}
        if ttl_secs > 0:
            payload_obj["ttl_secs"] = ttl_secs
        payload = json.dumps(payload_obj).encode("utf-8")
        status, body = self._request(
            "/keys/ttl-by-pattern",
            method="POST",
            body=payload,
            content_type="application/json",
            extra_headers=self._namespace_headers(namespace),
        )
        self._assert_ok(status, body)
        data = json.loads(body)
        return DittoSetTtlByPatternResult(updated=data["updated"])

    def stats(self) -> DittoStatsResult:
        """Return cache statistics for this node. Available on HTTP client only."""
        status, body = self._request('/stats')
        self._assert_ok(status, body)
        data = json.loads(body)
        fields = {f.name for f in dataclasses.fields(DittoStatsResult)}
        return DittoStatsResult(**{k: v for k, v in data.items() if k in fields})
