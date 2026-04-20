/** Value returned by get(). */
export interface DittoGetResult {
  value:   Buffer;
  version: number;
}

/** Value returned by set(). */
export interface DittoSetResult {
  version: number;
}

/** Value returned by deleteByPattern(). */
export interface DittoDeleteByPatternResult {
  deleted: number;
}

/** Value returned by setTtlByPattern(). */
export interface DittoSetTtlByPatternResult {
  updated: number;
}

/** Value returned by stats(). */
export interface DittoStatsResult {
  node_id:               string;
  status:                string;
  is_primary:            boolean;
  committed_index:       number;
  key_count:             number;
  memory_used_bytes:     number;
  memory_max_bytes:      number;
  evictions:             number;
  hit_count:             number;
  miss_count:            number;
  uptime_secs:           number;
  value_size_limit_bytes: number;
  max_keys_limit:        number;
  compression_enabled:   boolean;
  compression_threshold_bytes: number;
  node_name:             string;
  backup_dir_bytes:      number;
  persistence_platform_allowed: boolean;
  persistence_runtime_enabled: boolean;
  persistence_enabled: boolean;
  persistence_backup_enabled: boolean;
  persistence_export_enabled: boolean;
  persistence_import_enabled: boolean;
  rate_limit_enabled: boolean;
  rate_limited_requests_total: number;
  circuit_breaker_enabled: boolean;
  circuit_breaker_state: string;
  circuit_breaker_open_total: number;
  circuit_breaker_reject_total: number;
}

/** Error codes returned by the server. */
export type DittoErrorCode =
  | 'NodeInactive'
  | 'NoQuorum'
  | 'KeyNotFound'
  | 'InternalError'
  | 'WriteTimeout'
  | 'ValueTooLarge'
  | 'KeyLimitReached'
  | 'RateLimited'
  | 'CircuitOpen'
  | 'AuthFailed';

/** Error thrown when the server returns an error response. */
export class DittoError extends Error {
  readonly code: DittoErrorCode | string;

  constructor(code: DittoErrorCode | string, message: string) {
    super(message);
    this.name  = 'DittoError';
    this.code  = code;
  }
}
