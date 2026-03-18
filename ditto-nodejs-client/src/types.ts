/** Value returned by get(). */
export interface DittoGetResult {
  value:   Buffer;
  version: number;
}

/** Value returned by set(). */
export interface DittoSetResult {
  version: number;
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
  | 'AuthFailed';

/** Error thrown when the server returns an error response. */
export class DittoError extends Error {
  readonly code: DittoErrorCode;

  constructor(code: DittoErrorCode, message: string) {
    super(message);
    this.name  = 'DittoError';
    this.code  = code;
  }
}
