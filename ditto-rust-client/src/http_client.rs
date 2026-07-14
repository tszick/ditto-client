use std::time::Duration;
use std::fs;

use base64::Engine;
use reqwest::{Certificate, Client, Method, StatusCode};
use serde::Deserialize;
use serde_json::json;

use crate::client_internal::{
    atomic_http_unsupported_error_body, is_atomic_unsupported_status, namespace_header_value,
};
use crate::errors::{DittoError, Result};
use crate::types::{
    CounterResult, DeleteByPatternResult, GetResult, SetNxResult, SetResult, SetTtlByPatternResult,
    StatsResult,
};
use crate::validation::{normalized_namespace, validate_core_inputs, validate_pattern_inputs};

#[derive(Debug, Clone)]
pub struct HttpClientOptions {
    pub host: String,
    pub port: u16,
    pub tls: bool,
    pub username: Option<String>,
    pub password: Option<String>,
    pub request_timeout: Duration,
    pub connect_timeout: Duration,
    pub strict_mode: bool,
    pub dev_insecure_tls: bool,
    pub trusted_cert_path: Option<String>,
}

impl Default for HttpClientOptions {
    fn default() -> Self {
        Self {
            host: "localhost".to_string(),
            port: 7778,
            tls: false,
            username: None,
            password: None,
            request_timeout: Duration::from_secs(10),
            connect_timeout: Duration::from_secs(10),
            strict_mode: false,
            dev_insecure_tls: false,
            trusted_cert_path: None,
        }
    }
}

#[derive(Debug, Clone)]
pub struct DittoHttpClient {
    base_url: String,
    client: Client,
    username: Option<String>,
    password: Option<String>,
    strict_mode: bool,
}

impl DittoHttpClient {
    pub fn new(options: HttpClientOptions) -> Result<Self> {
        let scheme = if options.tls { "https" } else { "http" };
        if options.tls && options.dev_insecure_tls {
            return Err(DittoError::Validation(
                "dev_insecure_tls=true is insecure and is no longer supported. Use a trusted certificate configuration instead.".to_string(),
            ));
        }
        let mut builder = Client::builder()
            .connect_timeout(options.connect_timeout)
            .timeout(options.request_timeout)
            .danger_accept_invalid_certs(false);
        if let Some(path) = options
            .trusted_cert_path
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty())
        {
            let pem = fs::read(path).map_err(|e| {
                DittoError::Validation(format!("failed to read trusted_cert_path {path:?}: {e}"))
            })?;
            let cert = Certificate::from_pem(&pem).map_err(|e| {
                DittoError::Validation(format!("failed to parse trusted_cert_path {path:?}: {e}"))
            })?;
            builder = builder.add_root_certificate(cert);
        }
        let client = builder.build()?;

        Ok(Self {
            base_url: format!("{scheme}://{}:{}", options.host, options.port),
            client,
            username: options.username,
            password: options.password,
            strict_mode: options.strict_mode,
        })
    }

    pub async fn ping(&self) -> Result<bool> {
        let response = self.request(Method::GET, "/ping", None, None).await?;
        if response.status() != StatusCode::OK {
            return Ok(false);
        }
        let body: PingResponse = response.json().await?;
        Ok(body.pong)
    }

    pub async fn get(&self, key: &str, namespace: Option<&str>) -> Result<Option<GetResult>> {
        let namespace = normalized_namespace(self.strict_mode, namespace)?;
        validate_core_inputs(self.strict_mode, "get", key, namespace.as_deref())?;
        let path = format!("/key/{}", url_encode(key));
        let response = self
            .request(Method::GET, &path, namespace.as_deref(), None)
            .await?;
        if response.status() == StatusCode::NOT_FOUND {
            return Ok(None);
        }
        let response = self.assert_ok(response).await?;
        let body: GetResponse = response.json().await?;
        let value = match body.value_base64 {
            Some(value_base64) if !value_base64.is_empty() => base64::engine::general_purpose::STANDARD
                .decode(value_base64.trim())
                .map_err(|e| DittoError::Protocol(format!("invalid value_base64 in HTTP response: {e}")))?,
            _ => body.value.unwrap_or_default().into_bytes(),
        };
        Ok(Some(GetResult {
            value,
            version: body.version,
        }))
    }

    pub async fn set(
        &self,
        key: &str,
        value: impl Into<Vec<u8>>,
        ttl_secs: Option<u64>,
        namespace: Option<&str>,
    ) -> Result<SetResult> {
        let namespace = normalized_namespace(self.strict_mode, namespace)?;
        validate_core_inputs(self.strict_mode, "set", key, namespace.as_deref())?;
        let mut path = format!("/key/{}", url_encode(key));
        if let Some(ttl) = ttl_secs.filter(|ttl| *ttl > 0) {
            path.push_str(&format!("?ttl={ttl}"));
        }
        let body = value.into();
        let response = self
            .request(
                Method::PUT,
                &path,
                namespace.as_deref(),
                Some(("text/plain", body)),
            )
            .await?;
        let response = self.assert_ok(response).await?;
        Ok(response.json().await?)
    }

    pub async fn set_string(
        &self,
        key: &str,
        value: &str,
        ttl_secs: Option<u64>,
        namespace: Option<&str>,
    ) -> Result<SetResult> {
        self.set(key, value.as_bytes().to_vec(), ttl_secs, namespace)
            .await
    }

    /// Atomic create-if-absent. Returns `created=false` (and the existing
    /// version) when the key already exists — no write is performed.
    pub async fn set_nx(
        &self,
        key: &str,
        value: impl Into<Vec<u8>>,
        ttl_secs: Option<u64>,
        namespace: Option<&str>,
    ) -> Result<SetNxResult> {
        let namespace = normalized_namespace(self.strict_mode, namespace)?;
        validate_core_inputs(self.strict_mode, "set", key, namespace.as_deref())?;
        let mut path = format!("/key/{}?nx=1", url_encode(key));
        if let Some(ttl) = ttl_secs.filter(|ttl| *ttl > 0) {
            path.push_str(&format!("&ttl={ttl}"));
        }
        let response = self
            .request(
                Method::POST,
                &path,
                namespace.as_deref(),
                Some(("application/octet-stream", value.into())),
            )
            .await?;
        if is_atomic_unsupported_status(response.status()) {
            let body = response.bytes().await?;
            return Err(atomic_http_unsupported_error_body(&body, "SET_NX"));
        }
        let response = self.assert_ok(response).await?;
        let body: SetNxResponse = response.json().await?;
        Ok(SetNxResult {
            created: body.created,
            version: parse_u64_field(&body.version, "version")?,
        })
    }

    /// Atomic counter increment. Creates the key at `delta` if absent
    /// (with `ttl_secs_on_create`); never resets the TTL of an existing key.
    pub async fn incr(
        &self,
        key: &str,
        delta: i64,
        ttl_secs_on_create: Option<u64>,
        namespace: Option<&str>,
    ) -> Result<CounterResult> {
        let namespace = normalized_namespace(self.strict_mode, namespace)?;
        validate_core_inputs(self.strict_mode, "set", key, namespace.as_deref())?;
        // Send delta as a JSON string so the int64 survives any consumer that
        // would otherwise coerce a large number to a float (server accepts both).
        let payload = if let Some(ttl) = ttl_secs_on_create.filter(|ttl| *ttl > 0) {
            json!({ "delta": delta.to_string(), "ttl_secs_on_create": ttl })
        } else {
            json!({ "delta": delta.to_string() })
        };
        let path = format!("/key/{}/incr", url_encode(key));
        let response = self
            .request(
                Method::POST,
                &path,
                namespace.as_deref(),
                Some(("application/json", serde_json::to_vec(&payload)?)),
            )
            .await?;
        if is_atomic_unsupported_status(response.status()) {
            let body = response.bytes().await?;
            return Err(atomic_http_unsupported_error_body(&body, "INCR"));
        }
        let response = self.assert_ok(response).await?;
        let body: IncrResponse = response.json().await?;
        Ok(CounterResult {
            value: parse_i64_field(&body.value, "value")?,
            version: parse_u64_field(&body.version, "version")?,
        })
    }

    pub async fn delete(&self, key: &str, namespace: Option<&str>) -> Result<bool> {
        let namespace = normalized_namespace(self.strict_mode, namespace)?;
        validate_core_inputs(self.strict_mode, "delete", key, namespace.as_deref())?;
        let path = format!("/key/{}", url_encode(key));
        let response = self
            .request(Method::DELETE, &path, namespace.as_deref(), None)
            .await?;
        match response.status() {
            StatusCode::NO_CONTENT => Ok(true),
            StatusCode::NOT_FOUND => Ok(false),
            _ => {
                let _ = self.assert_ok(response).await?;
                Ok(true)
            }
        }
    }

    pub async fn delete_by_pattern(
        &self,
        pattern: &str,
        namespace: Option<&str>,
    ) -> Result<DeleteByPatternResult> {
        let namespace = normalized_namespace(self.strict_mode, namespace)?;
        validate_pattern_inputs(
            self.strict_mode,
            "deleteByPattern",
            pattern,
            namespace.as_deref(),
        )?;
        let payload = serde_json::to_vec(&json!({ "pattern": pattern }))?;
        let response = self
            .request(
                Method::POST,
                "/keys/delete-by-pattern",
                namespace.as_deref(),
                Some(("application/json", payload)),
            )
            .await?;
        let response = self.assert_ok(response).await?;
        Ok(response.json().await?)
    }

    pub async fn set_ttl_by_pattern(
        &self,
        pattern: &str,
        ttl_secs: Option<u64>,
        namespace: Option<&str>,
    ) -> Result<SetTtlByPatternResult> {
        let namespace = normalized_namespace(self.strict_mode, namespace)?;
        validate_pattern_inputs(
            self.strict_mode,
            "setTtlByPattern",
            pattern,
            namespace.as_deref(),
        )?;
        let payload = if let Some(ttl) = ttl_secs.filter(|ttl| *ttl > 0) {
            serde_json::to_vec(&json!({ "pattern": pattern, "ttl_secs": ttl }))?
        } else {
            serde_json::to_vec(&json!({ "pattern": pattern }))?
        };
        let response = self
            .request(
                Method::POST,
                "/keys/ttl-by-pattern",
                namespace.as_deref(),
                Some(("application/json", payload)),
            )
            .await?;
        let response = self.assert_ok(response).await?;
        Ok(response.json().await?)
    }

    pub async fn stats(&self) -> Result<StatsResult> {
        let response = self.request(Method::GET, "/stats", None, None).await?;
        let response = self.assert_ok(response).await?;
        Ok(response.json().await?)
    }

    async fn request(
        &self,
        method: Method,
        path: &str,
        namespace: Option<&str>,
        body: Option<(&str, Vec<u8>)>,
    ) -> Result<reqwest::Response> {
        let mut request = self
            .client
            .request(method, format!("{}{}", self.base_url, path));
        if let (Some(username), Some(password)) = (&self.username, &self.password) {
            request = request.basic_auth(username, Some(password));
        }
        if let Some(namespace) = namespace_header_value(namespace) {
            request = request.header("X-Ditto-Namespace", namespace);
        }
        if let Some((content_type, body)) = body {
            request = request.header("Content-Type", content_type).body(body);
        }
        Ok(request.send().await?)
    }

    async fn assert_ok(&self, response: reqwest::Response) -> Result<reqwest::Response> {
        if response.status().is_success() {
            return Ok(response);
        }
        let status = response.status();
        let body = response.bytes().await?;
        let mut code = http_status_to_code(status).to_string();
        let mut message = String::from_utf8_lossy(&body).to_string();
        if let Ok(payload) = serde_json::from_slice::<ErrorResponse>(&body) {
            if let Some(error) = payload.error.filter(|value| !value.is_empty()) {
                code = error;
            }
            if let Some(payload_message) = payload.message.filter(|value| !value.is_empty()) {
                message = payload_message;
            } else if message.is_empty() {
                message = code.clone();
            }
        }
        Err(DittoError::server(code, message))
    }
}

fn parse_u64_field(raw: &str, field: &str) -> Result<u64> {
    raw.parse::<u64>()
        .map_err(|_| DittoError::Protocol(format!("invalid {field} in HTTP response: {raw:?}")))
}

fn parse_i64_field(raw: &str, field: &str) -> Result<i64> {
    raw.parse::<i64>()
        .map_err(|_| DittoError::Protocol(format!("invalid {field} in HTTP response: {raw:?}")))
}

fn http_status_to_code(status: StatusCode) -> &'static str {
    match status.as_u16() {
        503 => "NodeInactive",
        504 => "WriteTimeout",
        404 => "KeyNotFound",
        _ => "InternalError",
    }
}

fn url_encode(value: &str) -> String {
    value
        .bytes()
        .flat_map(|byte| match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                vec![byte as char]
            }
            _ => format!("%{byte:02X}").chars().collect(),
        })
        .collect()
}

#[derive(Deserialize)]
struct PingResponse {
    pong: bool,
}

#[derive(Deserialize)]
struct GetResponse {
    value: Option<String>,
    value_base64: Option<String>,
    version: u64,
}

#[derive(Deserialize)]
struct ErrorResponse {
    error: Option<String>,
    message: Option<String>,
}

#[derive(Deserialize)]
struct SetNxResponse {
    created: bool,
    version: String,
}

#[derive(Deserialize)]
struct IncrResponse {
    value: String,
    version: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dev_insecure_tls_is_rejected() {
        let err = DittoHttpClient::new(HttpClientOptions {
            tls: true,
            dev_insecure_tls: true,
            ..HttpClientOptions::default()
        })
        .unwrap_err();
        assert!(err.to_string().contains("no longer supported"));
    }

    #[test]
    fn trusted_cert_path_defaults_to_none() {
        let options = HttpClientOptions::default();
        assert!(options.trusted_cert_path.is_none());
    }
}
