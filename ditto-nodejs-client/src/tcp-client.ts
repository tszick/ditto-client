/**
 * DittoTcpClient – connects directly to dittod TCP port 7777.
 *
 * Uses the bincode 1.x binary protocol over a persistent TCP connection.
 * Requests are serialized; only one in-flight request at a time.
 *
 * Usage:
 *   const client = new DittoTcpClient({ host: 'localhost' });
 *   await client.connect();
 *   await client.set('key', 'value', 60);
 *   const result = await client.get('key');
 *   await client.close();
 */

import * as net from 'node:net';
import {
  encodeGet,
  encodeSet,
  encodeDelete,
  encodePing,
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
}

export class DittoTcpClient {
  private readonly host: string;
  private readonly port: number;
  private readonly authToken?: string;
  private socket:   net.Socket | null = null;

  // Incoming byte accumulator and pending request queue.
  private recvBuf: Buffer = Buffer.alloc(0);
  private pending: Array<{
    resolve: (r: ClientResponse) => void;
    reject:  (e: Error) => void;
  }> = [];

  constructor(opts: DittoTcpClientOptions = {}) {
    this.host = opts.host ?? 'localhost';
    this.port = opts.port ?? 7777;
    this.authToken = opts.authToken;
  }

  // ---------------------------------------------------------------------------
  // Connection lifecycle
  // ---------------------------------------------------------------------------

  /** Open the TCP connection. Must be called before any other method. */
  async connect(): Promise<void> {
    if (this.socket) {
      return;
    }
    await new Promise<void>((resolve, reject) => {
      const sock = new net.Socket();
      sock.connect(this.port, this.host, () => {
        this.socket = sock;
        resolve();
      });
      sock.on('data', (chunk: Buffer) => this.onData(chunk));
      sock.on('error', (err) => this.onError(err));
      sock.on('close', () => this.onClose());
      sock.once('error', reject); // capture connection errors
    });
    if (this.authToken) {
      const { encodeAuth } = await import('./bincode.js');
      const resp = await this.send(encodeAuth(this.authToken));
      if (resp.type === 'Error') {
        this.close();
        throw new DittoError(resp.code, resp.message);
      }
      if (resp.type !== 'AuthOk') {
        this.close();
        throw new Error(`Unexpected auth response: ${resp.type}`);
      }
    }
  }

  /** Gracefully close the connection. */
  close(): Promise<void> {
    return new Promise((resolve) => {
      if (!this.socket) { resolve(); return; }
      this.socket.end(() => resolve());
      this.socket = null;
    });
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

  // ---------------------------------------------------------------------------
  // Internal send / receive
  // ---------------------------------------------------------------------------

  private send(frame: Buffer): Promise<ClientResponse> {
    return new Promise((resolve, reject) => {
      if (!this.socket) {
        reject(new Error('Not connected. Call connect() first.'));
        return;
      }
      this.pending.push({ resolve, reject });
      this.socket.write(frame);
    });
  }

  private onData(chunk: Buffer): void {
    this.recvBuf = Buffer.concat([this.recvBuf, chunk]);

    // Consume as many complete messages as possible.
    while (this.recvBuf.length >= 4) {
      const payloadLen = this.recvBuf.readUInt32BE(0);
      const totalLen   = 4 + payloadLen;
      if (this.recvBuf.length < totalLen) break;

      const payload = this.recvBuf.subarray(4, totalLen);
      this.recvBuf  = this.recvBuf.subarray(totalLen);

      const waiter = this.pending.shift();
      if (!waiter) continue;

      try {
        waiter.resolve(decodeResponse(payload));
      } catch (err) {
        waiter.reject(err instanceof Error ? err : new Error(String(err)));
      }
    }
  }

  private onError(err: Error): void {
    for (const w of this.pending) w.reject(err);
    this.pending = [];
    this.socket  = null;
  }

  private onClose(): void {
    const err = new Error('Connection closed by server.');
    for (const w of this.pending) w.reject(err);
    this.pending = [];
    this.socket  = null;
  }
}
