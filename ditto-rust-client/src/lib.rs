//! Async Rust client library for Ditto (`dittod`).

pub mod errors;
pub mod http_client;
pub mod tcp_client;
pub mod types;
pub mod validation;
pub mod wire;

pub use errors::{DittoError, Result};
pub use http_client::{DittoHttpClient, HttpClientOptions};
pub use tcp_client::{DittoTcpClient, TcpClientOptions};
pub use types::{
    DeleteByPatternResult, GetResult, SetResult, SetTtlByPatternResult, StatsResult,
    WatchEventResult,
};
