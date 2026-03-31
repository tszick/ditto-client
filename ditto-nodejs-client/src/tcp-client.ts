/**
 * DittoTcpClient – connects directly to dittod TCP port 7777.
 *
 * Uses the bincode 1.x binary protocol over a persistent TCP connection.
 * Requests are serialized; only one in-flight request at a time.
 *
 * ## Basic usage (manual lifecycle)
 *   const client = new DittoTcpClient({ host: 'localhost' });
 *   await client.connect();
 *   await client.set('key', 'value', 60);
 *   const result = await client.get('key');
 *   await client.close();
 *
 * ## Auto-reconnect mode (DITTO-01)
 *   const client = new DittoTcpClient({ host: 'localhost', autoReconnect: true });
 *   await client.connect(); // initial connect; reconnects automatically on loss
 *   await client.set('key', 'value');
 *   client.destroy(); // shut down permanently
 *
 * In auto-reconnect mode:
 * - Requests sent while disconnected are queued and flushed after reconnection.
 * - Reconnect uses exponential backoff (baseBackoffMs → maxBackoffMs).
 * - If maxReconnectAttempts (> 0) is reached, the offline queue is rejected
 *   and no further reconnect attempts are made (circuit open).
 */

import * as net from 'node:net';
import {
  encodeAuth,
  encodeGet,
  encodeSet,
  encodeDelete,
  encodePing,
  encodeWatch,
  encodeUnwatch,
  decodeResponse,
  type ClientResponse,
} from './bincode.js';
import { DittoError } from './types.js';
import type { DittoGetResult, DittoSetResult } from './types.js';

export interface DittoTcpClientOptions {
  /** Hostname or IP address of the dittod node. Default: 'localhost' */
  host?: string;
  /** TCP port. Default: 7777 */
  port?: number;
  /** Optional TCP auth token. */
  authToken?: string;

  // ── Auto-reconnect options (DITTO-01) ──────────────────────────────────────
  /**
   * Automatically reconnect on disconnect with exponential backoff.
   * When enabled, requests sent while disconnected are queued and retried.
   * Default: false
   */
  autoReconnect?: boolean;
  /**
   * Maximum number of consecutive reconnect attempts before the circuit
   * opens and all queued requests are rejected. 0 = unlimited.
   * Default: 0
   */
  maxReconnectAttempts?: number;
  /** Base reconnect backoff in milliseconds. Default: 200 */
  baseBackoffMs?: number;
  /** Maximum reconnect backoff in milliseconds. Default: 30_000 */
  maxBackoffMs?: number;
  /** Socket inactivity timeout in milliseconds. Default: 10000 */
  requestTimeoutMs?: number;
  /** Maximum accepted response frame size in bytes. Default: 8 MiB */
  maxFrameBytes?: number;
}

/** A waiter represents one pending request (sent or queued). */
interface Waiter {
  resolve: (r: ClientResponse) => void;
  reject:  (e: Error) => void;
  /** Encoded frame to (re)send after reconnect. Null for requests already sent. */
  frame:   Buffer | null;
}

export class DittoTcpClient {
  private readonly host: string;
  private readonly port: number;
  private readonly authToken?: string;

  private readonly autoReconnect:        boolean;
  private readonly maxReconnectAttempts: number;
  private readonly baseBackoffMs:        number;
  private readonly maxBackoffMs:         number;
  private readonly requestTimeoutMs:     number;
  private readonly maxFrameBytes:        number;

  private socket:   net.Socket | null = null;
  private recvBuf:  Buffer = Buffer.alloc(0);

  // DITTO-02: watch callbacks keyed by watched key.
  // WatchEvent frames from the server are routed here instead of the inflight queue.
  private watchCallbacks: Map<string, (value: Buffer | null, version: number) => void> = new Map();

  /**
   * Requests that have been SENT and are awaiting a response (ordered FIFO).
   * `frame` is null — the bytes are already on the wire.
   */
  private inflight: Waiter[] = [];

  /**
   * Requests queued while disconnected (not yet written to the socket).
   * Flushed in order after reconnect + re-auth.
   */
  private offlineQueue: Waiter[] = [];

  private reconnectAttempts = 0;
  private reconnecting      = false;
  private destroyed         = false;
  private reconnectTimer:   ReturnType<typeof setTimeout> | null = null;

  constructor(opts: DittoTcpClientOptions = {}) {
    this.host = opts.host ?? 'localhost';
    this.port = opts.port ?? 7777;
    this.authToken = opts.authToken;

    this.autoReconnect        = opts.autoReconnect        ?? false;
    this.maxReconnectAttempts = opts.maxReconnectAttempts ?? 0;
    this.baseBackoffMs        = opts.baseBackoffMs        ?? 200;
    this.maxBackoffMs         = opts.maxBackoffMs         ?? 30_000;
    this.requestTimeoutMs     = opts.requestTimeoutMs     ?? 10_000;
    this.maxFrameBytes        = opts.maxFrameBytes        ?? (8 * 1024 * 1024);
  }

  // ---------------------------------------------------------------------------
  // Connection lifecycle
  // ---------------------------------------------------------------------------

  /** Open the TCP connection. Must be called before any other method. */
  async connect(): Promise<void> {
    if (this.destroyed) throw new Error('Client has been destroyed.');
    if (this.socket)    return;
    await this.doConnect();
  }

  /** Gracefully close the connection. Disables auto-reconnect permanently. */
  async close(): Promise<void> {
    this.destroyed = true;
    this.cancelReconnectTimer();
    this.rejectOfflineQueue(new Error('Client closed.'));
    await new Promise<void>((resolve) => {
      if (!this.socket) { resolve(); return; }
      this.socket.end(() => resolve());
      this.socket = null;
    });
  }

  /**
   * Shut down the client immediately (alias for close).
   * Rejects all queued requests and cancels pending reconnects.
   */
  destroy(): void {
    void this.close();
  }

  // ---------------------------------------------------------------------------
  // Commands
  // ---------------------------------------------------------------------------

  /** Send a Ping and return true when Pong is received. */
  async ping(): Promise<boolean> {
    const resp = await this.send(encodePing());
    return resp.type === 'Pong';
  }

  /**
   * Get a key. Returns `null` when the key does not exist or has expired.
   * The returned `value` is a raw Buffer (the stored bytes, unchanged).
   */
  async get(key: string): Promise<DittoGetResult | null> {
    const resp = await this.send(encodeGet(key));
    if (resp.type === 'NotFound') return null;
    if (resp.type === 'Value')    return { value: resp.value, version: resp.version };
    if (resp.type === 'Error')    throw new DittoError(resp.code, resp.message);
    throw new Error(`Unexpected response: ${resp.type}`);
  }

  /**
   * Set a key. `value` can be a string (UTF-8 encoded) or a Buffer.
   * `ttlSecs` is optional; 0 or omitted means no expiry.
   */
  async set(
    key:     string,
    value:   string | Buffer,
    ttlSecs?: number,
  ): Promise<DittoSetResult> {
    const valueBuf = typeof value === 'string' ? Buffer.from(value, 'utf8') : value;
    const resp     = await this.send(encodeSet(key, valueBuf, ttlSecs));
    if (resp.type === 'Ok')    return { version: resp.version };
    if (resp.type === 'Error') throw new DittoError(resp.code, resp.message);
    throw new Error(`Unexpected response: ${resp.type}`);
  }

  /**
   * Delete a key. Returns `true` if the key existed, `false` if not found.
   */
  async delete(key: string): Promise<boolean> {
    const resp = await this.send(encodeDelete(key));
    if (resp.type === 'Deleted')  return true;
    if (resp.type === 'NotFound') return false;
    if (resp.type === 'Error')    throw new DittoError(resp.code, resp.message);
    throw new Error(`Unexpected response: ${resp.type}`);
  }

  // DITTO-02: watch / unwatch ------------------------------------------------

  /**
   * Subscribe to changes on `key`. The `callback` is called whenever the
   * server pushes a WatchEvent for this key. `value` is null when the key
   * was deleted. Multiple calls for the same key replace the previous callback.
   *
   * The subscription is active until `unwatch(key)` is called or the client
   * is closed. After an auto-reconnect the subscription is re-registered.
   */
  async watch(key: string, callback: (value: Buffer | null, version: number) => void): Promise<void> {
    this.watchCallbacks.set(key, callback);
    const resp = await this.send(encodeWatch(key));
    if (resp.type === 'Error') throw new DittoError(resp.code, resp.message);
    if (resp.type !== 'Watching') throw new Error(`Unexpected response to Watch: ${resp.type}`);
  }

  /**
   * Cancel the subscription for `key`. No-op if the key is not being watched.
   */
  async unwatch(key: string): Promise<void> {
    this.watchCallbacks.delete(key);
    if (!this.socket) return; // already disconnected — nothing to send
    const resp = await this.send(encodeUnwatch(key));
    if (resp.type === 'Error') throw new DittoError(resp.code, resp.message);
    if (resp.type !== 'Unwatched') throw new Error(`Unexpected response to Unwatch: ${resp.type}`);
  }

  // ---------------------------------------------------------------------------
  // Internal: send / receive
  // ---------------------------------------------------------------------------

  private send(frame: Buffer): Promise<ClientResponse> {
    return new Promise((resolve, reject) => {
      if (this.destroyed) {
        reject(new Error('Client has been destroyed.'));
        return;
      }

      if (!this.socket) {
        if (this.autoReconnect) {
          // Queue for later; circuit breaker check happens on reconnect failure.
          this.offlineQueue.push({ resolve, reject, frame });
          return;
        }
        reject(new Error('Not connected. Call connect() first.'));
        return;
      }

      // Connected: send immediately and register as in-flight.
      this.inflight.push({ resolve, reject, frame: null });
      this.socket.write(frame);
    });
  }

  private onData(chunk: Buffer): void {
    this.recvBuf = Buffer.concat([this.recvBuf, chunk]);

    while (this.recvBuf.length >= 4) {
      const payloadLen = this.recvBuf.readUInt32BE(0);
      if (payloadLen > this.maxFrameBytes) {
        this.socket?.destroy(
          new Error(`Incoming frame too large (${payloadLen} bytes > limit ${this.maxFrameBytes} bytes)`),
        );
        return;
      }
      const totalLen   = 4 + payloadLen;
      if (this.recvBuf.length < totalLen) break;

      const payload = this.recvBuf.subarray(4, totalLen);
      this.recvBuf  = this.recvBuf.subarray(totalLen);

      let decoded: ReturnType<typeof decodeResponse>;
      try {
        decoded = decodeResponse(payload);
      } catch (err) {
        // Malformed frame — reject the next waiter so the queue doesn't stall.
        const waiter = this.inflight.shift();
        waiter?.reject(err instanceof Error ? err : new Error(String(err)));
        continue;
      }

      // DITTO-02: WatchEvent frames are server-push; route to callback, not inflight queue.
      if (decoded.type === 'WatchEvent') {
        const cb = this.watchCallbacks.get(decoded.key);
        cb?.(decoded.value, decoded.version);
        continue;
      }

      const waiter = this.inflight.shift();
      if (!waiter) continue;
      waiter.resolve(decoded);
    }
  }

  private onError(err: Error): void {
    this.handleDisconnect(err);
  }

  private onClose(): void {
    this.handleDisconnect(new Error('Connection closed by server.'));
  }

  private handleDisconnect(err: Error): void {
    this.socket  = null;
    this.recvBuf = Buffer.alloc(0);

    // Move in-flight requests back to the front of the offline queue so they
    // are retried in order after reconnect.
    const toRetry = this.inflight.splice(0);
    // Re-attach the original frame so we can resend — but in-flight requests
    // had frame=null, meaning the frame is already lost. We cannot recover the
    // frame here. We must reject these specific requests.
    // (Keeping frame=null requests in the queue would stall the queue forever.)
    for (const w of toRetry) {
      if (w.frame !== null) {
        // Queued but not yet written — safe to retry.
        this.offlineQueue.unshift(w);
      } else {
        // Already written — response may or may not have been processed.
        // Reject with a retryable error message.
        w.reject(new Error(`Connection lost before response was received: ${err.message}`));
      }
    }

    if (this.autoReconnect && !this.destroyed) {
      this.scheduleReconnect();
    } else {
      this.rejectOfflineQueue(err);
    }
  }

  // ---------------------------------------------------------------------------
  // Internal: auto-reconnect logic (DITTO-01)
  // ---------------------------------------------------------------------------

  private scheduleReconnect(): void {
    if (this.reconnecting || this.destroyed) return;

    // Circuit breaker: stop if max attempts exceeded.
    if (this.maxReconnectAttempts > 0 && this.reconnectAttempts >= this.maxReconnectAttempts) {
      const err = new Error(
        `Circuit open: ${this.reconnectAttempts} reconnect attempts failed for ${this.host}:${this.port}`,
      );
      this.rejectOfflineQueue(err);
      return;
    }

    const backoff = Math.min(
      this.baseBackoffMs * Math.pow(2, this.reconnectAttempts),
      this.maxBackoffMs,
    );
    this.reconnecting = true;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.reconnecting   = false;
      void this.doConnect().catch(() => {
        // doConnect already handles scheduling the next attempt on failure.
      });
    }, backoff);
  }

  private cancelReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.reconnecting = false;
  }

  private async doConnect(): Promise<void> {
    if (this.destroyed) return;

    try {
      await new Promise<void>((resolve, reject) => {
        const sock = new net.Socket();
        sock.connect(this.port, this.host, () => {
          this.socket = sock;
          resolve();
        });
        sock.on('data',  (chunk: Buffer) => this.onData(chunk));
        sock.on('error', (err: Error)    => this.onError(err));
        sock.on('close', ()              => this.onClose());
        sock.setTimeout(this.requestTimeoutMs);
        sock.on('timeout', () => {
          sock.destroy(new Error(`Socket timeout after ${this.requestTimeoutMs}ms`));
        });
        sock.once('error', reject); // capture initial connect errors
      });
    } catch {
      this.socket = null;
      this.reconnectAttempts++;
      if (this.autoReconnect && !this.destroyed) {
        this.scheduleReconnect();
      } else {
        this.rejectOfflineQueue(new Error(`Failed to connect to ${this.host}:${this.port}`));
      }
      return;
    }

    // Connected — reset backoff counter.
    this.reconnectAttempts = 0;

    // Authenticate before flushing queued requests.
    if (this.authToken) {
      const authFrame = encodeAuth(this.authToken);
      let authResp: ClientResponse;
      try {
        authResp = await new Promise<ClientResponse>((resolve, reject) => {
          this.inflight.push({ resolve, reject, frame: null });
          this.socket!.write(authFrame);
        });
      } catch (err) {
        // Auth send failed — socket already disconnected; reconnect loop will retry.
        return;
      }
      if (authResp.type === 'Error') {
        await this.close();
        this.rejectOfflineQueue(new DittoError(authResp.code, authResp.message));
        return;
      }
      if (authResp.type !== 'AuthOk') {
        await this.close();
        this.rejectOfflineQueue(new Error(`Unexpected auth response: ${authResp.type}`));
        return;
      }
    }

    // DITTO-02: re-register active watch subscriptions before flushing the queue.
    await this.reRegisterWatches();

    // Flush offline queue.
    await this.flushOfflineQueue();
  }

  /** Re-send Watch for all active callbacks after a reconnect. */
  private async reRegisterWatches(): Promise<void> {
    for (const key of this.watchCallbacks.keys()) {
      if (!this.socket) break;
      // Send Watch frame directly (bypass send() to avoid adding to inflight before auth).
      const frame = encodeWatch(key);
      await new Promise<void>((resolve, reject) => {
        this.inflight.push({
          resolve: () => resolve(),
          reject,
          frame: null,
        });
        this.socket!.write(frame);
      });
    }
  }

  private async flushOfflineQueue(): Promise<void> {
    while (this.offlineQueue.length > 0 && this.socket) {
      const waiter = this.offlineQueue.shift()!;
      if (waiter.frame === null) {
        // Should not happen after handleDisconnect logic, but guard anyway.
        waiter.reject(new Error('Internal: queued request has no frame to send.'));
        continue;
      }
      this.inflight.push({ resolve: waiter.resolve, reject: waiter.reject, frame: null });
      this.socket.write(waiter.frame);
    }
  }

  private rejectOfflineQueue(err: Error): void {
    const queue = this.offlineQueue.splice(0);
    for (const w of queue) w.reject(err);
  }
}
