use std::collections::HashMap;
use std::fs;
use std::sync::Arc;

use ditto_rust_client::{DittoHttpClient, HttpClientOptions};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;

#[derive(Deserialize)]
struct ContractSuite {
    cases: Vec<ContractCase>,
}

#[derive(Deserialize)]
struct ContractCase {
    id: String,
    operation: String,
    inputs: serde_json::Map<String, Value>,
    expect: serde_json::Map<String, Value>,
}

#[derive(Clone, Default)]
struct Store {
    entries: Arc<Mutex<HashMap<String, Entry>>>,
    version: Arc<Mutex<u64>>,
}

#[derive(Clone)]
struct Entry {
    value: String,
    version: u64,
}

#[tokio::test]
async fn core_ops_contract_runtime_http() {
    let raw = fs::read_to_string("../contracts/core-ops.contract.json").expect("read contract");
    let suite: ContractSuite = serde_json::from_str(&raw).expect("parse contract");
    let store = Store::default();
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    let port = listener.local_addr().expect("local addr").port();
    let server_store = store.clone();
    let server = tokio::spawn(async move {
        loop {
            let Ok((stream, _)) = listener.accept().await else {
                break;
            };
            let store = server_store.clone();
            tokio::spawn(async move {
                let _ = handle_connection(stream, store).await;
            });
        }
    });

    let client = DittoHttpClient::new(HttpClientOptions {
        host: "127.0.0.1".to_string(),
        port,
        ..HttpClientOptions::default()
    })
    .expect("client");

    for case in suite.cases {
        match case.operation.as_str() {
            "ping" => {
                let pong = client.ping().await.expect(&case.id);
                assert_eq!(pong, case.expect["value"].as_bool().unwrap());
            }
            "set_get" => {
                let key = case.inputs["key"].as_str().unwrap();
                let value = case.inputs["value"].as_str().unwrap();
                let ttl = case.inputs["ttl_secs"].as_u64();
                client
                    .set_string(key, value, ttl, None)
                    .await
                    .expect(&case.id);
                let got = client.get(key, None).await.expect(&case.id).expect("value");
                assert_eq!(
                    String::from_utf8(got.value).unwrap(),
                    case.expect["value_equals"].as_str().unwrap()
                );
            }
            "delete" => {
                let key = case.inputs["key"].as_str().unwrap();
                let deleted = client.delete(key, None).await.expect(&case.id);
                assert_eq!(deleted, case.expect["value"].as_bool().unwrap());
            }
            "delete_by_pattern" => {
                client
                    .set_string("contract:prefix:a", "a", None, None)
                    .await
                    .expect("seed a");
                client
                    .set_string("contract:prefix:b", "b", None, None)
                    .await
                    .expect("seed b");
                let pattern = case.inputs["pattern"].as_str().unwrap();
                let out = client
                    .delete_by_pattern(pattern, None)
                    .await
                    .expect(&case.id);
                assert!(out.deleted >= case.expect["min"].as_u64().unwrap());
            }
            other => panic!("unsupported contract operation: {other}"),
        }
    }

    server.abort();
}

async fn handle_connection(mut stream: TcpStream, store: Store) -> std::io::Result<()> {
    let mut buf = vec![0u8; 8192];
    let mut read = 0usize;
    loop {
        let n = stream.read(&mut buf[read..]).await?;
        if n == 0 {
            return Ok(());
        }
        read += n;
        if read >= 4 && buf[..read].windows(4).any(|w| w == b"\r\n\r\n") {
            break;
        }
    }
    let request = String::from_utf8_lossy(&buf[..read]).to_string();
    let header_end = request.find("\r\n\r\n").unwrap() + 4;
    let headers = &request[..header_end];
    let mut lines = headers.lines();
    let request_line = lines.next().unwrap_or_default();
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or_default();
    let path = parts.next().unwrap_or_default();
    let content_length = headers
        .lines()
        .find_map(|line| line.strip_prefix("Content-Length: "))
        .and_then(|value| value.trim().parse::<usize>().ok())
        .unwrap_or(0);
    let mut body = buf[header_end..read].to_vec();
    while body.len() < content_length {
        let n = stream.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        body.extend_from_slice(&buf[..n]);
    }

    let (status, response_body) = route(method, path, &body, store).await;
    let status_text = match status {
        200 => "OK",
        204 => "No Content",
        404 => "Not Found",
        _ => "Internal Server Error",
    };
    let response = if status == 204 {
        format!("HTTP/1.1 {status} {status_text}\r\nContent-Length: 0\r\n\r\n")
    } else {
        format!(
            "HTTP/1.1 {status} {status_text}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
            response_body.len(),
            response_body
        )
    };
    stream.write_all(response.as_bytes()).await
}

async fn route(method: &str, path: &str, body: &[u8], store: Store) -> (u16, String) {
    if method == "GET" && path == "/ping" {
        return (200, json!({ "pong": true }).to_string());
    }

    if let Some(raw_key) = path.strip_prefix("/key/") {
        let key = decode_path(raw_key.split('?').next().unwrap_or(raw_key));
        match method {
            "PUT" => {
                let mut version = store.version.lock().await;
                *version += 1;
                let entry = Entry {
                    value: String::from_utf8_lossy(body).to_string(),
                    version: *version,
                };
                store.entries.lock().await.insert(key, entry);
                return (200, json!({ "version": *version }).to_string());
            }
            "GET" => {
                if let Some(entry) = store.entries.lock().await.get(&key).cloned() {
                    return (
                        200,
                        json!({ "value": entry.value, "version": entry.version }).to_string(),
                    );
                }
                return (404, String::new());
            }
            "DELETE" => {
                let existed = store.entries.lock().await.remove(&key).is_some();
                return if existed {
                    (204, String::new())
                } else {
                    (404, String::new())
                };
            }
            _ => {}
        }
    }

    if method == "POST" && path == "/keys/delete-by-pattern" {
        let payload: Value = serde_json::from_slice(body).unwrap_or_else(|_| json!({}));
        let prefix = payload["pattern"]
            .as_str()
            .unwrap_or_default()
            .trim_end_matches('*');
        let mut entries = store.entries.lock().await;
        let before = entries.len();
        entries.retain(|key, _| !key.starts_with(prefix));
        let deleted = before - entries.len();
        return (200, json!({ "deleted": deleted }).to_string());
    }

    (404, String::new())
}

fn decode_path(value: &str) -> String {
    let mut out = Vec::with_capacity(value.len());
    let bytes = value.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let Ok(hex) = u8::from_str_radix(&value[i + 1..i + 3], 16)
        {
            out.push(hex);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}
