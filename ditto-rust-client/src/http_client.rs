use std::time::Duration;

use reqwest::{Client, Method, StatusCode};
use serde::Deserialize;
use serde_json::json;

use crate::errors::{DittoError, Result};
use crate::types::{
    DeleteByPatternResult, GetResult, SetResult, SetTtlByPatternResult, StatsResult,
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
            eprintln!(
                "WARNING: insecure TLS certificate verification is enabled for local development only; do not use dev_insecure_tls in production"
            );
        }
        let client = Client::builder()
            .connect_timeout(options.connect_timeout)
            .timeout(options.request_timeout)
            .danger_accept_invalid_certs(options.tls && options.dev_insecure_tls)
            .build()?;

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
        Ok(Some(GetResult {
            value: body.value.into_bytes(),
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
        if let Some(namespace) = namespace.filter(|ns| !ns.trim().is_empty()) {
            request = request.header("X-Ditto-Namespace", namespace.trim());
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
    value: String,
    version: u64,
}

#[derive(Deserialize)]
struct ErrorResponse {
    error: Option<String>,
    message: Option<String>,
}
