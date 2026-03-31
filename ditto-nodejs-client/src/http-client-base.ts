/**
 * DittoHttpClientBase – infrastructure for the generated DittoHttpClient.
 *
 * This file is maintained MANUALLY. It contains the constructor, options,
 * and internal helpers (request, assertOk). The public endpoint methods
 * live in http-client.ts, which is GENERATED from api/ditto-http-api.yaml.
 */

import * as https from 'node:https';
import { DittoError } from './types.js';
import type { DittoErrorCode } from './types.js';

export interface DittoHttpClientOptions {
  /** Hostname or IP address of the dittod node. Default: 'localhost' */
  host?: string;
  /** HTTP(S) port. Default: 7778 */
  port?: number;
  /** Use HTTPS. Default: false */
  tls?: boolean;
  /** HTTP Basic Auth username. */
  username?: string;
  /** HTTP Basic Auth password. */
  password?: string;
  /**
   * Reject unauthorized TLS certificates.
   * Set to false to accept self-signed certs. Default: true.
   */
  rejectUnauthorized?: boolean;
  /** Request timeout in milliseconds. Default: 10000 */
  timeoutMs?: number;
}

export class DittoHttpClientBase {
  private readonly baseUrl:    string;
  private readonly authHeader: string | undefined;
  private readonly agent:      https.Agent | undefined;
  private readonly timeoutMs:  number;

  constructor(opts: DittoHttpClientOptions = {}) {
    const scheme = opts.tls ? 'https' : 'http';
    const port   = opts.port ?? 7778;
    const host   = opts.host ?? 'localhost';
    this.baseUrl = `${scheme}://${host}:${port}`;

    if (opts.username && opts.password) {
      const creds     = Buffer.from(`${opts.username}:${opts.password}`).toString('base64');
      this.authHeader = `Basic ${creds}`;
    }

    if (opts.tls) {
      this.agent = new https.Agent({
        rejectUnauthorized: opts.rejectUnauthorized ?? true,
      });
    }

    this.timeoutMs = opts.timeoutMs ?? 10_000;
  }

  // ── Shared infrastructure (used by generated endpoint methods) ──────────────

  /** No-op for API symmetry with DittoTcpClient. */
  close(): void { /* HTTP is stateless – nothing to close */ }

  /** @internal */
  protected async request(path: string, init: RequestInit = {}): Promise<Response> {
    const headers: Record<string, string> = {
      ...(init.headers as Record<string, string> | undefined),
    };
    if (this.authHeader) headers['Authorization'] = this.authHeader;

    const url = `${this.baseUrl}${path}`;

    if (this.agent) {
      return this.requestHttps(url, init.method ?? 'GET', headers, init.body);
    }

    return fetch(url, {
      ...init,
      headers,
      signal: init.signal ?? AbortSignal.timeout(this.timeoutMs),
    });
  }

  private requestHttps(
    url: string,
    method: string,
    headers: Record<string, string>,
    body: unknown,
  ): Promise<Response> {
    return new Promise<Response>((resolve, reject) => {
      const req = https.request(url, { method, headers, agent: this.agent }, (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (chunk) => chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)));
        res.on('end', () => {
          const status = res.statusCode ?? 500;
          const statusText = res.statusMessage ?? 'Unknown';
          const responseHeaders = new Headers();
          for (const [k, v] of Object.entries(res.headers)) {
            if (typeof v === 'string') responseHeaders.set(k, v);
            else if (Array.isArray(v)) responseHeaders.set(k, v.join(', '));
          }
          const responseBody = (status === 204 || status === 205 || status === 304)
            ? null
            : Buffer.concat(chunks);
          resolve(new Response(responseBody, {
            status,
            statusText,
            headers: responseHeaders,
          }));
        });
      });

      req.setTimeout(this.timeoutMs, () => {
        req.destroy(new Error(`HTTPS request timeout after ${this.timeoutMs}ms`));
      });
      req.on('error', reject);

      if (body === undefined || body === null) {
        req.end();
        return;
      }

      if (typeof body === 'string') {
        req.end(body);
        return;
      }

      if (body instanceof Buffer || body instanceof Uint8Array) {
        req.end(body);
        return;
      }

      reject(new Error('Unsupported HTTPS request body type'));
    });
  }

  /** @internal */
  protected async assertOk(resp: Response): Promise<void> {
    if (resp.ok) return;
    let message = resp.statusText;
    try {
      const body = await resp.json() as { error?: string; message?: string };
      message = body.message ?? body.error ?? message;
    } catch { /* ignore parse errors */ }
    const code: DittoErrorCode =
      resp.status === 503 ? 'NodeInactive'  :
      resp.status === 504 ? 'WriteTimeout'  :
      resp.status === 404 ? 'KeyNotFound'   : 'InternalError';
    throw new DittoError(code, message);
  }
}
