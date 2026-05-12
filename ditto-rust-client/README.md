# ditto-rust-client

Async Rust client library for Ditto (`dittod`) with HTTP and TCP clients.

## Requirements

- Rust 1.95+
- Tokio runtime

## Quick usage

```rust
use ditto_rust_client::{DittoHttpClient, HttpClientOptions};

#[tokio::main]
async fn main() -> ditto_rust_client::Result<()> {
    let client = DittoHttpClient::new(HttpClientOptions::default())?;
    client.set_string("k", "v", Some(60), None).await?;

    if let Some(value) = client.get("k", None).await? {
        println!("{}", String::from_utf8_lossy(&value.value));
    }

    Ok(())
}
```

## TLS behavior

- TLS certificate verification is enabled by default when `tls: true`.
- `dev_insecure_tls: true` is only for local/self-signed development and requires `DITTO_CLIENT_ALLOW_INSECURE_TLS_DEV=true`.
- Production clients should keep certificate verification enabled.

## Tests

```bash
cargo test
```
