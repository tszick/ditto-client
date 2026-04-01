package ditto

type GetResult struct {
	Value   []byte
	Version uint64
}

type SetResult struct {
	Version uint64
}

type DeleteByPatternResult struct {
	Deleted uint64 `json:"deleted"`
}

type SetTtlByPatternResult struct {
	Updated uint64 `json:"updated"`
}

type StatsResult struct {
	NodeID                    string `json:"node_id"`
	Status                    string `json:"status"`
	IsPrimary                 bool   `json:"is_primary"`
	CommittedIndex            uint64 `json:"committed_index"`
	KeyCount                  uint64 `json:"key_count"`
	MemoryUsedBytes           uint64 `json:"memory_used_bytes"`
	MemoryMaxBytes            uint64 `json:"memory_max_bytes"`
	Evictions                 uint64 `json:"evictions"`
	HitCount                  uint64 `json:"hit_count"`
	MissCount                 uint64 `json:"miss_count"`
	UptimeSecs                uint64 `json:"uptime_secs"`
	ValueSizeLimitBytes       uint64 `json:"value_size_limit_bytes"`
	MaxKeysLimit              uint64 `json:"max_keys_limit"`
	CompressionEnabled        bool   `json:"compression_enabled"`
	CompressionThresholdBytes uint64 `json:"compression_threshold_bytes"`
	NodeName                  string `json:"node_name"`
	BackupDirBytes            uint64 `json:"backup_dir_bytes"`
}
