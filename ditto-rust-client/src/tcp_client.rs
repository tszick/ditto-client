use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::time::timeout;

use crate::errors::{DittoError, Result};
use crate::types::{
    DeleteByPatternResult, GetResult, SetResult, SetTtlByPatternResult, WatchEventResult,
};
use crate::validation::{normalized_namespace, validate_core_inputs, validate_pattern_inputs};
use crate::wire::{self, ClientResponse};

#[derive(Debug, Clone)]
pub struct TcpClientOptions {
    pub host: String,
    pub port: u16,
    pub auth_token: Option<String>,
    pub connect_timeout: Duration,
    pub request_timeout: Duration,
    pub max_frame_bytes: u32,
    pub strict_mode: bool,
    pub auto_reconnect: bool,
}

impl Default for TcpClientOptions {
    fn default() -> Self {
        Self {
            host: "localhost".to_string(),
            port: 7777,
            auth_token: None,
            connect_timeout: Duration::from_secs(10),
            request_timeout: Duration::from_secs(10),
            max_frame_bytes: 8 * 1024 * 1024,
            strict_mode: false,
            auto_reconnect: false,
        }
    }
}

#[derive(Debug)]
pub struct DittoTcpClient {
    options: TcpClientOptions,
    stream: Mutex<Option<TcpStream>>,
}

impl DittoTcpClient {
    pub fn new(options: TcpClientOptions) -> Self {
        Self {
            options,
            stream: Mutex::new(None),
        }
    }

    pub async fn connect(&self) -> Result<()> {
        let mut guard = self.stream.lock().await;
        if guard.is_some() {
            return Ok(());
        }
        let stream = self.open_stream().await?;
        *guard = Some(stream);
        if let Some(token) = &self.options.auth_token {
            let response = self
                .send_locked(&mut guard, wire::encode_auth(token))
                .await?;
            match response {
                ClientResponse::AuthOk => {}
                ClientResponse::Error { code, message } => {
                    *guard = None;
                    return Err(DittoError::server(code, message));
                }
                _ => {
                    *guard = None;
                    return Err(DittoError::Protocol("unexpected auth response".into()));
                }
            }
        }
        Ok(())
    }

    pub async fn close(&self) -> Result<()> {
        let mut guard = self.stream.lock().await;
        if let Some(mut stream) = guard.take() {
            stream.shutdown().await?;
        }
        Ok(())
    }

    pub async fn ping(&self) -> Result<bool> {
        let response = self.send_request(wire::encode_ping()).await?;
        Ok(matches!(response, ClientResponse::Pong))
    }

    pub async fn get(&self, key: &str, namespace: Option<&str>) -> Result<Option<GetResult>> {
        let namespace = normalized_namespace(self.options.strict_mode, namespace)?;
        validate_core_inputs(self.options.strict_mode, "get", key, namespace.as_deref())?;
        let response = self
            .send_request(wire::encode_get(key, namespace.as_deref()))
            .await?;
        match response {
            ClientResponse::NotFound => Ok(None),
            ClientResponse::Value { value, version, .. } => Ok(Some(GetResult { value, version })),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Err(DittoError::Protocol("unexpected get response".into())),
        }
    }

    pub async fn set(
        &self,
        key: &str,
        value: impl Into<Vec<u8>>,
        ttl_secs: Option<u64>,
        namespace: Option<&str>,
    ) -> Result<SetResult> {
        let value = value.into();
        let namespace = normalized_namespace(self.options.strict_mode, namespace)?;
        validate_core_inputs(self.options.strict_mode, "set", key, namespace.as_deref())?;
        let ttl = ttl_secs.filter(|ttl| *ttl > 0);
        let response = self
            .send_request(wire::encode_set(key, &value, ttl, namespace.as_deref()))
            .await?;
        match response {
            ClientResponse::Ok { version } => Ok(SetResult { version }),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Err(DittoError::Protocol("unexpected set response".into())),
        }
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
        let namespace = normalized_namespace(self.options.strict_mode, namespace)?;
        validate_core_inputs(
            self.options.strict_mode,
            "delete",
            key,
            namespace.as_deref(),
        )?;
        let response = self
            .send_request(wire::encode_delete(key, namespace.as_deref()))
            .await?;
        match response {
            ClientResponse::Deleted => Ok(true),
            ClientResponse::NotFound => Ok(false),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Err(DittoError::Protocol("unexpected delete response".into())),
        }
    }

    pub async fn delete_by_pattern(
        &self,
        pattern: &str,
        namespace: Option<&str>,
    ) -> Result<DeleteByPatternResult> {
        let namespace = normalized_namespace(self.options.strict_mode, namespace)?;
        validate_pattern_inputs(
            self.options.strict_mode,
            "deleteByPattern",
            pattern,
            namespace.as_deref(),
        )?;
        let response = self
            .send_request(wire::encode_delete_by_pattern(
                pattern,
                namespace.as_deref(),
            ))
            .await?;
        match response {
            ClientResponse::PatternDeleted { deleted } => Ok(DeleteByPatternResult { deleted }),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Err(DittoError::Protocol(
                "unexpected delete_by_pattern response".into(),
            )),
        }
    }

    pub async fn set_ttl_by_pattern(
        &self,
        pattern: &str,
        ttl_secs: Option<u64>,
        namespace: Option<&str>,
    ) -> Result<SetTtlByPatternResult> {
        let namespace = normalized_namespace(self.options.strict_mode, namespace)?;
        validate_pattern_inputs(
            self.options.strict_mode,
            "setTtlByPattern",
            pattern,
            namespace.as_deref(),
        )?;
        let response = self
            .send_request(wire::encode_set_ttl_by_pattern(
                pattern,
                ttl_secs.filter(|ttl| *ttl > 0),
                namespace.as_deref(),
            ))
            .await?;
        match response {
            ClientResponse::PatternTtlUpdated { updated } => Ok(SetTtlByPatternResult { updated }),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Err(DittoError::Protocol(
                "unexpected set_ttl_by_pattern response".into(),
            )),
        }
    }

    pub async fn watch(&self, key: &str, namespace: Option<&str>) -> Result<()> {
        let namespace = normalized_namespace(self.options.strict_mode, namespace)?;
        validate_core_inputs(self.options.strict_mode, "watch", key, namespace.as_deref())?;
        let response = self
            .send_request(wire::encode_watch(key, namespace.as_deref()))
            .await?;
        match response {
            ClientResponse::Watching => Ok(()),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Err(DittoError::Protocol("unexpected watch response".into())),
        }
    }

    pub async fn unwatch(&self, key: &str, namespace: Option<&str>) -> Result<()> {
        let namespace = normalized_namespace(self.options.strict_mode, namespace)?;
        validate_core_inputs(
            self.options.strict_mode,
            "unwatch",
            key,
            namespace.as_deref(),
        )?;
        let response = self
            .send_request(wire::encode_unwatch(key, namespace.as_deref()))
            .await?;
        match response {
            ClientResponse::Unwatched => Ok(()),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Err(DittoError::Protocol("unexpected unwatch response".into())),
        }
    }

    pub async fn wait_watch_event(&self) -> Result<WatchEventResult> {
        let mut guard = self.stream.lock().await;
        self.ensure_connected_locked(&mut guard).await?;
        let response = self.read_response_locked(&mut guard).await?;
        match response {
            ClientResponse::WatchEvent {
                key,
                value,
                version,
            } => Ok(WatchEventResult {
                key,
                value,
                version,
            }),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Err(DittoError::Protocol(
                "unexpected watch event response".into(),
            )),
        }
    }

    async fn send_request(&self, frame: Vec<u8>) -> Result<ClientResponse> {
        let mut guard = self.stream.lock().await;
        self.ensure_connected_locked(&mut guard).await?;
        match self.send_locked(&mut guard, frame.clone()).await {
            Ok(response) => Ok(response),
            Err(err) => {
                *guard = None;
                if self.options.auto_reconnect {
                    self.ensure_connected_locked(&mut guard).await?;
                    self.send_locked(&mut guard, frame).await
                } else {
                    Err(err)
                }
            }
        }
    }

    async fn ensure_connected_locked(&self, guard: &mut Option<TcpStream>) -> Result<()> {
        if guard.is_some() {
            return Ok(());
        }
        let stream = self.open_stream().await?;
        *guard = Some(stream);
        if let Some(token) = &self.options.auth_token {
            let response = self.send_locked(guard, wire::encode_auth(token)).await?;
            match response {
                ClientResponse::AuthOk => {}
                ClientResponse::Error { code, message } => {
                    *guard = None;
                    return Err(DittoError::server(code, message));
                }
                _ => {
                    *guard = None;
                    return Err(DittoError::Protocol("unexpected auth response".into()));
                }
            }
        }
        Ok(())
    }

    async fn open_stream(&self) -> Result<TcpStream> {
        let addr = format!("{}:{}", self.options.host, self.options.port);
        let stream = timeout(self.options.connect_timeout, TcpStream::connect(addr))
            .await
            .map_err(|_| DittoError::Protocol("tcp connect timed out".into()))??;
        Ok(stream)
    }

    async fn send_locked(
        &self,
        guard: &mut Option<TcpStream>,
        frame: Vec<u8>,
    ) -> Result<ClientResponse> {
        let stream = guard
            .as_mut()
            .ok_or_else(|| DittoError::Protocol("tcp stream is not connected".into()))?;
        timeout(self.options.request_timeout, stream.write_all(&frame))
            .await
            .map_err(|_| DittoError::Protocol("tcp write timed out".into()))??;
        self.read_response_locked(guard).await
    }

    async fn read_response_locked(&self, guard: &mut Option<TcpStream>) -> Result<ClientResponse> {
        let stream = guard
            .as_mut()
            .ok_or_else(|| DittoError::Protocol("tcp stream is not connected".into()))?;
        let mut header = [0u8; 4];
        timeout(self.options.request_timeout, stream.read_exact(&mut header))
            .await
            .map_err(|_| DittoError::Protocol("tcp read timed out".into()))??;
        let len = u32::from_be_bytes(header);
        if len > self.options.max_frame_bytes {
            return Err(DittoError::Protocol(format!(
                "incoming frame too large: {len}"
            )));
        }
        let mut payload = vec![0u8; len as usize];
        timeout(
            self.options.request_timeout,
            stream.read_exact(&mut payload),
        )
        .await
        .map_err(|_| DittoError::Protocol("tcp read timed out".into()))??;
        wire::decode_response(&payload)
    }
}
