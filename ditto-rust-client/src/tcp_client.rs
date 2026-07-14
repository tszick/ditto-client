use std::{path::Path, sync::Once, time::Duration};

use rustls::{pki_types::ServerName, ClientConfig, RootCertStore};
use rustls_pki_types::pem::PemObject;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::time::timeout;
use tokio_rustls::TlsConnector;

use crate::client_internal::normalize_atomic_tcp_error;
use crate::errors::{DittoError, Result};
use crate::types::{
    CounterResult, DeleteByPatternResult, GetResult, SetNxResult, SetResult, SetTtlByPatternResult,
    WatchEventResult,
};
use crate::validation::{normalized_namespace, validate_core_inputs, validate_pattern_inputs};
use crate::wire::{self, ClientResponse};

trait DynStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> DynStream for T where T: AsyncRead + AsyncWrite + Unpin + Send {}

type BoxedStream = Box<dyn DynStream>;

static RUSTLS_PROVIDER: Once = Once::new();

#[derive(Debug, Clone)]
pub struct TcpClientOptions {
    pub host: String,
    pub port: u16,
    pub auth_token: Option<String>,
    pub tls_enabled: bool,
    pub tls_ca_cert: Option<String>,
    pub tls_server_name: Option<String>,
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
            tls_enabled: false,
            tls_ca_cert: None,
            tls_server_name: None,
            connect_timeout: Duration::from_secs(10),
            request_timeout: Duration::from_secs(10),
            max_frame_bytes: 8 * 1024 * 1024,
            strict_mode: false,
            auto_reconnect: false,
        }
    }
}

pub struct DittoTcpClient {
    options: TcpClientOptions,
    stream: Mutex<Option<BoxedStream>>,
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
        self.authenticate_locked(&mut guard).await
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
        match response {
            ClientResponse::Pong => Ok(true),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Ok(false),
        }
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

    pub async fn set_nx(
        &self,
        key: &str,
        value: impl Into<Vec<u8>>,
        ttl_secs: Option<u64>,
        namespace: Option<&str>,
    ) -> Result<SetNxResult> {
        let value = value.into();
        let namespace = normalized_namespace(self.options.strict_mode, namespace)?;
        validate_core_inputs(self.options.strict_mode, "set", key, namespace.as_deref())?;
        let ttl = ttl_secs.filter(|ttl| *ttl > 0);
        let response = self
            .send_request(wire::encode_set_nx(key, &value, ttl, namespace.as_deref()))
            .await
            .map_err(|err| normalize_atomic_tcp_error(err, "SET_NX"))?;
        match response {
            ClientResponse::SetNx { created, version } => Ok(SetNxResult { created, version }),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Err(normalize_atomic_tcp_error(
                DittoError::Protocol("unexpected set_nx response".into()),
                "SET_NX",
            )),
        }
    }

    pub async fn incr(
        &self,
        key: &str,
        delta: i64,
        ttl_secs_on_create: Option<u64>,
        namespace: Option<&str>,
    ) -> Result<CounterResult> {
        let namespace = normalized_namespace(self.options.strict_mode, namespace)?;
        validate_core_inputs(self.options.strict_mode, "set", key, namespace.as_deref())?;
        let response = self
            .send_request(wire::encode_incr(
                key,
                delta,
                ttl_secs_on_create.filter(|ttl| *ttl > 0),
                namespace.as_deref(),
            ))
            .await
            .map_err(|err| normalize_atomic_tcp_error(err, "INCR"))?;
        match response {
            ClientResponse::Counter { value, version } => Ok(CounterResult { value, version }),
            ClientResponse::Error { code, message } => Err(DittoError::server(code, message)),
            _ => Err(normalize_atomic_tcp_error(
                DittoError::Protocol("unexpected incr response".into()),
                "INCR",
            )),
        }
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

    async fn ensure_connected_locked(&self, guard: &mut Option<BoxedStream>) -> Result<()> {
        if guard.is_some() {
            return Ok(());
        }
        let stream = self.open_stream().await?;
        *guard = Some(stream);
        self.authenticate_locked(guard).await
    }

    async fn authenticate_locked(&self, guard: &mut Option<BoxedStream>) -> Result<()> {
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

    async fn open_stream(&self) -> Result<BoxedStream> {
        let addr = format!("{}:{}", self.options.host, self.options.port);
        let stream = timeout(self.options.connect_timeout, TcpStream::connect(addr))
            .await
            .map_err(|_| DittoError::Protocol("tcp connect timed out".into()))??;

        if !self.options.tls_enabled {
            return Ok(Box::new(stream));
        }

        install_rustls_provider();
        let connector = build_tls_connector(self.options.tls_ca_cert.as_deref())?;
        let server_name = self
            .options
            .tls_server_name
            .clone()
            .unwrap_or_else(|| self.options.host.clone());
        let server_name = ServerName::try_from(server_name)
            .map_err(|_| DittoError::Protocol("invalid TLS server name".into()))?;
        let tls_stream = timeout(self.options.connect_timeout, connector.connect(server_name, stream))
            .await
            .map_err(|_| DittoError::Protocol("tls handshake timed out".into()))?
            .map_err(|err| DittoError::Protocol(format!("tls handshake failed: {err}")))?;
        Ok(Box::new(tls_stream))
    }

    async fn send_locked(
        &self,
        guard: &mut Option<BoxedStream>,
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

    async fn read_response_locked(&self, guard: &mut Option<BoxedStream>) -> Result<ClientResponse> {
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

fn install_rustls_provider() {
    RUSTLS_PROVIDER.call_once(|| {
        rustls::crypto::ring::default_provider()
            .install_default()
            .expect("failed to install rustls crypto provider");
    });
}

fn build_tls_connector(ca_cert_path: Option<&str>) -> Result<TlsConnector> {
    let path = ca_cert_path
        .filter(|path| !path.trim().is_empty())
        .ok_or_else(|| DittoError::Protocol("tls_ca_cert is required when tls_enabled=true".into()))?;
    let certs = load_certs(path)?;
    let mut root_store = RootCertStore::empty();
    for cert in certs {
        root_store
            .add(cert)
            .map_err(|err| DittoError::Protocol(format!("invalid TLS CA certificate: {err}")))?;
    }

    let client_config = ClientConfig::builder()
        .with_root_certificates(root_store)
        .with_no_client_auth();
    Ok(TlsConnector::from(std::sync::Arc::new(client_config)))
}

fn load_certs(path: &str) -> Result<Vec<rustls::pki_types::CertificateDer<'static>>> {
    rustls::pki_types::CertificateDer::pem_file_iter(Path::new(path))
        .map_err(|err| DittoError::Protocol(format!("opening TLS CA cert '{path}': {err}")))?
        .collect::<std::result::Result<Vec<_>, _>>()
        .map_err(|err| DittoError::Protocol(format!("reading TLS CA cert '{path}': {err}")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::test_support;
    use std::sync::Arc;
    use tokio::net::TcpListener;
    use tokio::sync::Mutex as TokioMutex;

    #[tokio::test]
    async fn tcp_core_operations_map_responses() {
        let port = spawn_scripted_server(vec![vec![
            test_support::frame_pong(),
            test_support::frame_ok(7),
            test_support::frame_value("k", b"value", 7),
            test_support::frame_deleted(),
            test_support::frame_pattern_deleted(2),
            test_support::frame_pattern_ttl_updated(3),
        ]])
        .await;
        let client = test_client(port, false, None);

        assert!(client.ping().await.unwrap());
        assert_eq!(
            client
                .set_string("k", "value", Some(60), None)
                .await
                .unwrap(),
            SetResult { version: 7 }
        );
        assert_eq!(
            client.get("k", None).await.unwrap(),
            Some(GetResult {
                value: b"value".to_vec(),
                version: 7
            })
        );
        assert!(client.delete("k", None).await.unwrap());
        assert_eq!(
            client.delete_by_pattern("k:*", None).await.unwrap(),
            DeleteByPatternResult { deleted: 2 }
        );
        assert_eq!(
            client
                .set_ttl_by_pattern("k:*", Some(10), None)
                .await
                .unwrap(),
            SetTtlByPatternResult { updated: 3 }
        );
    }

    #[tokio::test]
    async fn tcp_auth_and_watch_flow_map_responses() {
        let port = spawn_watch_server().await;
        let client = test_client(port, false, Some("secret"));

        client.connect().await.unwrap();
        client.watch("k", None).await.unwrap();
        assert_eq!(
            client.wait_watch_event().await.unwrap(),
            WatchEventResult {
                key: "k".to_string(),
                value: Some(b"value".to_vec()),
                version: 9,
            }
        );
        client.unwatch("k", None).await.unwrap();
    }

    #[tokio::test]
    async fn tcp_atomic_primitives_map_responses() {
        let port = spawn_scripted_server(vec![vec![
            test_support::frame_set_nx(true, 1),
            test_support::frame_set_nx(false, 1),
            test_support::frame_counter(5, 2),
            test_support::frame_counter(-3, 3),
        ]])
        .await;
        let client = test_client(port, false, None);

        assert_eq!(
            client
                .set_nx("k", b"v".to_vec(), Some(60), None)
                .await
                .unwrap(),
            crate::types::SetNxResult {
                created: true,
                version: 1
            }
        );
        assert_eq!(
            client
                .set_nx("k", b"v2".to_vec(), None, None)
                .await
                .unwrap(),
            crate::types::SetNxResult {
                created: false,
                version: 1
            }
        );
        assert_eq!(
            client.incr("c", 5, Some(30), None).await.unwrap(),
            crate::types::CounterResult {
                value: 5,
                version: 2
            }
        );
        assert_eq!(
            client.incr("c", -8, None, None).await.unwrap(),
            crate::types::CounterResult {
                value: -3,
                version: 3
            }
        );
    }

    #[tokio::test]
    async fn tcp_incr_type_mismatch_maps_to_ditto_error() {
        let port = spawn_scripted_server(vec![vec![test_support::frame_error(
            13,
            "Stored value is not a valid int64 counter",
        )]])
        .await;
        let client = test_client(port, false, None);

        match client.incr("c", 1, None, None).await.unwrap_err() {
            DittoError::Server { code, .. } => assert_eq!(code, "TypeMismatch"),
            other => panic!("unexpected error: {other:?}"),
        }
    }

    #[tokio::test]
    async fn tcp_get_not_found_maps_to_none() {
        let port = spawn_scripted_server(vec![vec![test_support::frame_not_found()]]).await;
        let client = test_client(port, false, None);

        assert_eq!(client.get("missing", None).await.unwrap(), None);
    }

    #[tokio::test]
    async fn tcp_server_error_maps_to_ditto_error() {
        let port =
            spawn_scripted_server(vec![vec![test_support::frame_error(7, "slow down")]]).await;
        let client = test_client(port, false, None);

        match client.ping().await.unwrap_err() {
            DittoError::Server { code, message } => {
                assert_eq!(code, "RateLimited");
                assert_eq!(message, "slow down");
            }
            other => panic!("unexpected error: {other:?}"),
        }
    }

    #[tokio::test]
    async fn tcp_auto_reconnect_retries_once_after_io_error() {
        let port = spawn_scripted_server(vec![vec![], vec![test_support::frame_pong()]]).await;
        let client = test_client(port, true, None);

        assert!(client.ping().await.unwrap());
    }

    #[test]
    fn tls_connector_requires_ca_cert_when_enabled() {
        let err = match build_tls_connector(None) {
            Ok(_) => panic!("connector unexpectedly succeeded without CA cert"),
            Err(err) => err,
        };
        assert!(err
            .to_string()
            .contains("tls_ca_cert is required when tls_enabled=true"));
    }

    fn test_client(port: u16, auto_reconnect: bool, auth_token: Option<&str>) -> DittoTcpClient {
        DittoTcpClient::new(TcpClientOptions {
            host: "127.0.0.1".to_string(),
            port,
            auth_token: auth_token.map(str::to_string),
            request_timeout: Duration::from_secs(2),
            connect_timeout: Duration::from_secs(2),
            auto_reconnect,
            ..TcpClientOptions::default()
        })
    }

    async fn spawn_scripted_server(connections: Vec<Vec<Vec<u8>>>) -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let scripts = Arc::new(TokioMutex::new(connections.into_iter()));
        tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = listener.accept().await else {
                    break;
                };
                let Some(responses) = scripts.lock().await.next() else {
                    break;
                };
                tokio::spawn(async move {
                    for response in responses {
                        if read_request_frame(&mut stream).await.is_err() {
                            return;
                        }
                        if stream.write_all(&response).await.is_err() {
                            return;
                        }
                    }
                    let _ = read_request_frame(&mut stream).await;
                });
            }
        });
        port
    }

    async fn spawn_watch_server() -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            let Ok((mut stream, _)) = listener.accept().await else {
                return;
            };
            if read_request_frame(&mut stream).await.is_err() {
                return;
            }
            if stream
                .write_all(&test_support::frame_auth_ok())
                .await
                .is_err()
            {
                return;
            }
            if read_request_frame(&mut stream).await.is_err() {
                return;
            }
            if stream
                .write_all(&test_support::frame_watching())
                .await
                .is_err()
            {
                return;
            }
            if stream
                .write_all(&test_support::frame_watch_event("k", Some(b"value"), 9))
                .await
                .is_err()
            {
                return;
            }
            if read_request_frame(&mut stream).await.is_err() {
                return;
            }
            let _ = stream.write_all(&test_support::frame_unwatched()).await;
        });
        port
    }

    async fn read_request_frame(stream: &mut TcpStream) -> std::io::Result<Vec<u8>> {
        let mut header = [0u8; 4];
        stream.read_exact(&mut header).await?;
        let len = u32::from_be_bytes(header) as usize;
        let mut payload = vec![0u8; len];
        stream.read_exact(&mut payload).await?;
        Ok(payload)
    }
}
