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
  /** Dev-only insecure TLS mode (accepts untrusted certs). Default: false */
  devInsecureTls?: boolean;
  /** Request timeout in milliseconds. Default: 10000 */
  timeoutMs?: number;
  /** Enable retry with exponential backoff on transient failures. Default: true */
  retryEnabled?: boolean;
  /** Maximum retry attempts (in addition to the first request). Default: 2 */
  maxRetries?: number;
  /** Base retry backoff in milliseconds. Default: 100 */
  retryBaseBackoffMs?: number;
  /** Maximum retry backoff in milliseconds. Default: 2000 */
  retryMaxBackoffMs?: number;
  /** Random jitter added to backoff in milliseconds. Default: 100 */
  retryJitterMs?: number;
  /** HTTP methods eligible for retry. Default: GET, DELETE */
  retryMethods?: string[];
  /** Enable circuit breaker around request execution. Default: false */
  circuitBreakerEnabled?: boolean;
  /** Consecutive failures to open circuit. Default: 5 */
  circuitFailureThreshold?: number;
  /** Open-state duration in milliseconds before half-open probe. Default: 5000 */
  circuitOpenMs?: number;
  /** Successful half-open probes needed to close circuit. Default: 2 */
  circuitHalfOpenMaxRequests?: number;
  /** Enable strict client-side request validation for key/namespace. Default: false */
  strictMode?: boolean;
}

export class DittoHttpClientBase {
  private readonly baseUrl:    string;
  private readonly authHeader: string | undefined;
  private readonly agent:      https.Agent | undefined;
  private readonly timeoutMs:  number;
  private readonly retryEnabled: boolean;
  private readonly maxRetries: number;
  private readonly retryBaseBackoffMs: number;
  private readonly retryMaxBackoffMs: number;
  private readonly retryJitterMs: number;
  private readonly retryMethods: Set<string>;
  private readonly circuitBreakerEnabled: boolean;
  private readonly circuitFailureThreshold: number;
  private readonly circuitOpenMs: number;
  private readonly circuitHalfOpenMaxRequests: number;
  private readonly strictMode: boolean;
  private circuitState: 'closed' | 'open' | 'half-open' = 'closed';
  private circuitFailures = 0;
  private circuitOpenUntilMs = 0;
  private halfOpenSuccesses = 0;

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
      const devInsecureTls = opts.devInsecureTls ?? false;
      this.agent = new https.Agent({
        rejectUnauthorized: devInsecureTls ? false : (opts.rejectUnauthorized ?? true),
      });
    }

    this.timeoutMs = opts.timeoutMs ?? 10_000;
    this.retryEnabled = opts.retryEnabled ?? true;
    this.maxRetries = Math.max(0, opts.maxRetries ?? 2);
    this.retryBaseBackoffMs = Math.max(1, opts.retryBaseBackoffMs ?? 100);
    this.retryMaxBackoffMs = Math.max(1, opts.retryMaxBackoffMs ?? 2_000);
    this.retryJitterMs = Math.max(0, opts.retryJitterMs ?? 100);
    this.retryMethods = new Set((opts.retryMethods ?? ['GET', 'DELETE']).map((m) => m.toUpperCase()));
    this.circuitBreakerEnabled = opts.circuitBreakerEnabled ?? false;
    this.circuitFailureThreshold = Math.max(1, opts.circuitFailureThreshold ?? 5);
    this.circuitOpenMs = Math.max(1, opts.circuitOpenMs ?? 5_000);
    this.circuitHalfOpenMaxRequests = Math.max(1, opts.circuitHalfOpenMaxRequests ?? 2);
    this.strictMode = opts.strictMode ?? false;
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
    const method = (init.method ?? 'GET').toUpperCase();
    const canRetry = this.retryEnabled && this.retryMethods.has(method);
    this.beforeRequest();

    let attempt = 0;
    // eslint-disable-next-line no-constant-condition
    while (true) {
      try {
        const resp = this.agent
          ? await this.requestHttps(url, method, headers, init.body)
          : await fetch(url, {
            ...init,
            method,
            headers,
            signal: init.signal ?? AbortSignal.timeout(this.timeoutMs),
          });

        const retryable = this.isRetryableStatus(resp.status);
        if (retryable && canRetry && attempt < this.maxRetries) {
          attempt += 1;
          await sleepMs(this.computeBackoffMs(attempt));
          continue;
        }

        if (retryable) {
          this.recordFailure();
        } else {
          this.recordSuccess();
        }
        return resp;
      } catch (err) {
        if (canRetry && attempt < this.maxRetries) {
          attempt += 1;
          await sleepMs(this.computeBackoffMs(attempt));
          continue;
        }
        this.recordFailure();
        throw err;
      }
    }
  }

  private beforeRequest(): void {
    if (!this.circuitBreakerEnabled) return;
    const nowMs = Date.now();
    if (this.circuitState === 'open') {
      if (nowMs >= this.circuitOpenUntilMs) {
        this.circuitState = 'half-open';
        this.halfOpenSuccesses = 0;
      } else {
        throw new DittoError('CircuitOpen', 'HTTP client circuit breaker is open');
      }
    }
  }

  private recordFailure(): void {
    if (!this.circuitBreakerEnabled) return;
    this.circuitFailures += 1;
    this.halfOpenSuccesses = 0;
    if (this.circuitState === 'half-open' || this.circuitFailures >= this.circuitFailureThreshold) {
      this.circuitState = 'open';
      this.circuitOpenUntilMs = Date.now() + this.circuitOpenMs;
    }
  }

  private recordSuccess(): void {
    if (!this.circuitBreakerEnabled) return;
    if (this.circuitState === 'half-open') {
      this.halfOpenSuccesses += 1;
      if (this.halfOpenSuccesses >= this.circuitHalfOpenMaxRequests) {
        this.circuitState = 'closed';
        this.circuitFailures = 0;
        this.halfOpenSuccesses = 0;
      }
      return;
    }
    this.circuitState = 'closed';
    this.circuitFailures = 0;
  }

  private isRetryableStatus(status: number): boolean {
    return status === 429 || status === 503 || status === 504;
  }

  private computeBackoffMs(attempt: number): number {
    const exp = Math.min(this.retryMaxBackoffMs, this.retryBaseBackoffMs * Math.pow(2, attempt - 1));
    const jitter = this.retryJitterMs > 0 ? Math.floor(Math.random() * (this.retryJitterMs + 1)) : 0;
    return exp + jitter;
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
    let bodyCode: string | undefined;
    try {
      const body = await resp.json() as { error?: string; message?: string };
      message = body.message ?? body.error ?? message;
      bodyCode = body.error;
    } catch { /* ignore parse errors */ }
    const code = this.mapHttpError(resp.status, bodyCode);
    throw new DittoError(code, message);
  }

  private mapHttpError(status: number, bodyCode?: string): DittoErrorCode {
    if (bodyCode) {
      const mapped = bodyCode as DittoErrorCode;
      if (isKnownErrorCode(mapped)) return mapped;
    }
    if (status === 429) return 'RateLimited';
    if (status === 503) return 'NodeInactive';
    if (status === 504) return 'WriteTimeout';
    if (status === 404) return 'KeyNotFound';
    return 'InternalError';
  }

  /** @internal */
  protected validateCoreInputs(op: 'get' | 'set' | 'delete', key: string, namespace?: string): void {
    if (!this.strictMode) return;
    const keyTrimmed = key.trim();
    if (keyTrimmed.length === 0) {
      throw new Error(`Invalid ${op} request: key must not be empty.`);
    }
    if (!STRICT_TOKEN_RE.test(key)) {
      throw new Error(
        `Invalid ${op} request: key contains unsupported characters. Allowed: [A-Za-z0-9._:-]`,
      );
    }
    if (namespace === undefined) return;
    const nsTrimmed = namespace.trim();
    if (nsTrimmed.length === 0) {
      throw new Error(`Invalid ${op} request: namespace must not be blank when provided.`);
    }
    if (nsTrimmed.includes('::')) {
      throw new Error(`Invalid ${op} request: namespace must not contain '::'.`);
    }
    if (!STRICT_TOKEN_RE.test(nsTrimmed)) {
      throw new Error(
        `Invalid ${op} request: namespace contains unsupported characters. Allowed: [A-Za-z0-9._:-]`,
      );
    }
  }

  /** @internal */
  protected validatePatternInputs(op: 'deleteByPattern' | 'setTtlByPattern', pattern: string, namespace?: string): void {
    if (!this.strictMode) return;
    const patternTrimmed = pattern.trim();
    if (patternTrimmed.length === 0) {
      throw new Error(`Invalid ${op} request: pattern must not be empty.`);
    }
    if (!STRICT_PATTERN_RE.test(patternTrimmed)) {
      throw new Error(
        `Invalid ${op} request: pattern contains unsupported characters. Allowed: [A-Za-z0-9._:-*]`,
      );
    }
    if (namespace === undefined) return;
    const nsTrimmed = namespace.trim();
    if (nsTrimmed.length === 0) {
      throw new Error(`Invalid ${op} request: namespace must not be blank when provided.`);
    }
    if (nsTrimmed.includes('::')) {
      throw new Error(`Invalid ${op} request: namespace must not contain '::'.`);
    }
    if (!STRICT_TOKEN_RE.test(nsTrimmed)) {
      throw new Error(
        `Invalid ${op} request: namespace contains unsupported characters. Allowed: [A-Za-z0-9._:-]`,
      );
    }
  }
}

const STRICT_TOKEN_RE = /^[A-Za-z0-9._:-]+$/;
const STRICT_PATTERN_RE = /^[A-Za-z0-9._:\-*]+$/;

function isKnownErrorCode(code: DittoErrorCode): boolean {
  return [
    'NodeInactive',
    'NoQuorum',
    'KeyNotFound',
    'InternalError',
    'WriteTimeout',
    'ValueTooLarge',
    'KeyLimitReached',
    'RateLimited',
    'CircuitOpen',
    'AuthFailed',
  ].includes(code);
}

function sleepMs(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
