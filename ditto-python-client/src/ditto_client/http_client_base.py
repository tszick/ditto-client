"""
DittoHttpClientBase – infrastructure for the generated DittoHttpClient.

This file is maintained MANUALLY.  It contains the constructor, options,
and internal helpers (_request, _assert_ok, _url_encode).  The public
endpoint methods live in http_client.py, which is GENERATED from
api/ditto-http-api.yaml.
"""

from __future__ import annotations

import base64
import json
import ssl
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

from .types import DittoError, DittoErrorCode


class DittoHttpClientBase:
    """Infrastructure base for the generated DittoHttpClient."""

    def __init__(
        self,
        host: str = "localhost",
        port: int = 7778,
        *,
        tls: bool = False,
        username: str | None = None,
        password: str | None = None,
        reject_unauthorized: bool = True,
        timeout_secs: float = 10.0,
        strict_mode: bool = False,
    ) -> None:
        scheme = "https" if tls else "http"
        self._base_url = f"{scheme}://{host}:{port}"
        self._auth_header: str | None = None
        self._ssl_ctx: ssl.SSLContext | None = None
        self._timeout_secs = timeout_secs
        self._strict_mode = strict_mode

        if username and password:
            creds = base64.b64encode(f"{username}:{password}".encode()).decode()
            self._auth_header = f"Basic {creds}"

        if tls:
            ctx = ssl.create_default_context()
            if not reject_unauthorized:
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
            self._ssl_ctx = ctx

    # ------------------------------------------------------------------
    # Context manager (HTTP is stateless – nothing to close)
    # ------------------------------------------------------------------

    def __enter__(self) -> DittoHttpClientBase:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def close(self) -> None:
        """No-op for API symmetry with DittoTcpClient (HTTP is stateless)."""

    # ------------------------------------------------------------------
    # Shared infrastructure (used by generated endpoint methods)
    # ------------------------------------------------------------------

    def _request(
        self,
        path: str,
        method: str = "GET",
        body: bytes | None = None,
        content_type: str | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, str]:
        """
        Send an HTTP request.  Returns ``(status_code, response_body_text)``.
        """
        url = self._base_url + path
        headers: dict[str, str] = {}
        if self._auth_header:
            headers["Authorization"] = self._auth_header
        if content_type:
            headers["Content-Type"] = content_type
        if extra_headers:
            headers.update(extra_headers)

        req = urllib.request.Request(
            url,
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(req, context=self._ssl_ctx, timeout=self._timeout_secs) as resp:
                return resp.status, resp.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            # HTTPError carries a status code and body.
            body_text = exc.read().decode("utf-8") if exc.fp else ""
            return exc.code, body_text

    def _assert_ok(self, status: int, body: str) -> None:
        """Raise DittoError for non-2xx responses."""
        if 200 <= status < 300:
            return
        message: str = body
        try:
            data: Any = json.loads(body)
            message = data.get("message") or data.get("error") or body
        except (ValueError, AttributeError):
            pass  # Non-JSON body; fall back to raw string as the error message
        if status == 503:
            code: DittoErrorCode = DittoErrorCode.NODE_INACTIVE
        elif status == 504:
            code = DittoErrorCode.WRITE_TIMEOUT
        elif status == 404:
            code = DittoErrorCode.KEY_NOT_FOUND
        else:
            code = DittoErrorCode.INTERNAL_ERROR
        raise DittoError(code, message)

    @staticmethod
    def _url_encode(s: str) -> str:
        """Percent-encode a key for use in a URL path segment."""
        return urllib.parse.quote(s, safe="")
