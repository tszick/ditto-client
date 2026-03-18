package io.ditto.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Cache statistics returned by {@code stats()} (HTTP client only). */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class DittoStatsResult {

    @JsonProperty("node_id")                    private String  nodeId;
    @JsonProperty("status")                     private String  status;
    @JsonProperty("is_primary")                 private boolean isPrimary;
    @JsonProperty("committed_index")            private long    committedIndex;
    @JsonProperty("key_count")                  private long    keyCount;
    @JsonProperty("memory_used_bytes")          private long    memoryUsedBytes;
    @JsonProperty("memory_max_bytes")           private long    memoryMaxBytes;
    @JsonProperty("evictions")                  private long    evictions;
    @JsonProperty("hit_count")                  private long    hitCount;
    @JsonProperty("miss_count")                 private long    missCount;
    @JsonProperty("uptime_secs")                private long    uptimeSecs;
    @JsonProperty("value_size_limit_bytes")     private long    valueSizeLimitBytes;
    @JsonProperty("max_keys_limit")             private long    maxKeysLimit;
    @JsonProperty("compression_enabled")        private boolean compressionEnabled;
    @JsonProperty("compression_threshold_bytes") private long   compressionThresholdBytes;
    @JsonProperty("node_name")                  private String  nodeName;
    @JsonProperty("backup_dir_bytes")           private long    backupDirBytes;

    // No-arg constructor required by Jackson
    public DittoStatsResult() {}

    public String  getNodeId()                    { return nodeId; }
    public String  getStatus()                    { return status; }
    public boolean isPrimary()                    { return isPrimary; }
    public long    getCommittedIndex()            { return committedIndex; }
    public long    getKeyCount()                  { return keyCount; }
    public long    getMemoryUsedBytes()           { return memoryUsedBytes; }
    public long    getMemoryMaxBytes()            { return memoryMaxBytes; }
    public long    getEvictions()                 { return evictions; }
    public long    getHitCount()                  { return hitCount; }
    public long    getMissCount()                 { return missCount; }
    public long    getUptimeSecs()                { return uptimeSecs; }
    public long    getValueSizeLimitBytes()       { return valueSizeLimitBytes; }
    public long    getMaxKeysLimit()              { return maxKeysLimit; }
    public boolean isCompressionEnabled()         { return compressionEnabled; }
    public long    getCompressionThresholdBytes() { return compressionThresholdBytes; }
    public String  getNodeName()                  { return nodeName; }
    public long    getBackupDirBytes()            { return backupDirBytes; }
}
