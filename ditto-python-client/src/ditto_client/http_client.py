# Generated from api/ditto-http-api.yaml v1.2.0
# DO NOT EDIT MANUALLY — regenerate with: cd src/tools && npm run generate

from __future__ import annotations

import dataclasses
import json

from .http_client_base import DittoHttpClientBase
from .types import (
    DittoDeleteByPatternResult,
    DittoGetResult,
    DittoSetResult,
    DittoSetTtlByPatternResult,
    DittoStatsResult,
)


class DittoHttpClient(DittoHttpClientBase):
    """HTTP client for the Ditto cache server (port 7778)."""

    # ── Generated endpoint methods (from api/ditto-http-api.yaml) ──────────

    def ping(self) -> bool:
        """Check whether the node is alive and accepting requests."""
        status, body = self._request('/ping')
        if status != 200:
            return False
        data = json.loads(body)
        return data.get('pong') is True

    def get(self, key: str) -> DittoGetResult | None:
        """Get a value by key. Returns null when the key does not exist or has expired."""
        status, body = self._request(f'/key/{self._url_encode(key)}')
        if status == 404:
            return None
        self._assert_ok(status, body)
        data = json.loads(body)
        return DittoGetResult(value=data['value'].encode('utf-8'), version=data['version'])

    def set(self, key: str, value: str, ttl_secs: int = 0) -> DittoSetResult:
        """Set a value. ttlSecs = 0 or omitted means no expiry."""
        path = f'/key/{self._url_encode(key)}'
        if ttl_secs > 0:
            path += f'?ttl={ttl_secs}'
        status, body = self._request(path, method='PUT', body=value.encode('utf-8'),
                                     content_type='text/plain')
        self._assert_ok(status, body)
        data = json.loads(body)
        return DittoSetResult(version=data['version'])

    def delete(self, key: str) -> bool:
        """Delete a key. Returns true if the key existed, false if not found."""
        status, body = self._request(f'/key/{self._url_encode(key)}', method='DELETE')
        if status == 404:
            return False
        if status == 204:
            return True
        self._assert_ok(status, body)
        return True

    def delete_by_pattern(self, pattern: str) -> DittoDeleteByPatternResult:
        """Delete all keys matching a glob-style pattern ('*' wildcard)."""
        payload = json.dumps({"pattern": pattern}).encode("utf-8")
        status, body = self._request(
            "/keys/delete-by-pattern",
            method="POST",
            body=payload,
            content_type="application/json",
        )
        self._assert_ok(status, body)
        data = json.loads(body)
        return DittoDeleteByPatternResult(deleted=data["deleted"])

    def set_ttl_by_pattern(self, pattern: str, ttl_secs: int = 0) -> DittoSetTtlByPatternResult:
        """
        Update TTL for all keys matching a glob-style pattern ('*' wildcard).
        ``ttl_secs <= 0`` removes TTL from matched keys.
        """
        payload_obj = {"pattern": pattern}
        if ttl_secs > 0:
            payload_obj["ttl_secs"] = ttl_secs
        payload = json.dumps(payload_obj).encode("utf-8")
        status, body = self._request(
            "/keys/ttl-by-pattern",
            method="POST",
            body=payload,
            content_type="application/json",
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
