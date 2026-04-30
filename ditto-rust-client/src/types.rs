use serde::Deserialize;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GetResult {
    pub value: Vec<u8>,
    pub version: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
pub struct SetResult {
    pub version: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
pub struct DeleteByPatternResult {
    pub deleted: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
pub struct SetTtlByPatternResult {
    pub updated: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WatchEventResult {
    pub key: String,
    pub value: Option<Vec<u8>>,
    pub version: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct StatsResult {
    pub node_id: String,
    pub status: String,
    pub is_primary: bool,
    pub committed_index: u64,
    pub key_count: u64,
    pub memory_used_bytes: u64,
    pub memory_max_bytes: u64,
    pub evictions: u64,
    pub hit_count: u64,
    pub miss_count: u64,
    pub uptime_secs: u64,
    #[serde(default)]
    pub value_size_limit_bytes: u64,
    #[serde(default)]
    pub max_keys_limit: u64,
    pub compression_enabled: bool,
    #[serde(default)]
    pub compression_threshold_bytes: u64,
    pub node_name: String,
    #[serde(default)]
    pub backup_dir_bytes: u64,
}
