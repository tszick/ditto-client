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
}

export class DittoHttpClientBase {
  private readonly baseUrl:    string;
  private readonly authHeader: string | undefined;
  private readonly agent:      https.Agent | undefined;

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

    const fetchOpts: RequestInit & { dispatcher?: unknown } = { ...init, headers };

    // Attach the https.Agent when TLS is enabled (undici / Node.js fetch path).
    if (this.agent) {
      (fetchOpts as Record<string, unknown>)['agent'] = this.agent;
    }

    return fetch(`${this.baseUrl}${path}`, fetchOpts);
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
