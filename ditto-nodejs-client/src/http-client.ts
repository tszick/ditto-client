/**
 * DittoHttpClient – generated from api/ditto-http-api.yaml v1.1.0
 *
 * DO NOT EDIT MANUALLY.
 * Regenerate with: cd src/tools && npm run generate
 */

import { DittoHttpClientBase } from './http-client-base.js';
import { DittoError } from './types.js';
import type {
  DittoCounterResult,
  DittoDeleteByPatternResult,
  DittoGetResult,
  DittoSetResult,
  DittoSetNxResult,
  DittoSetTtlByPatternResult,
  DittoStatsResult,
} from './types.js';

export class DittoHttpClient extends DittoHttpClientBase {
  private namespaceHeaders(namespace?: string): Record<string, string> | undefined {
    if (!namespace || namespace.trim() === '') return undefined;
    return { 'X-Ditto-Namespace': namespace };
  }

  // ── Generated endpoint methods (from api/ditto-http-api.yaml) ─────────────

  /** Check whether the node is alive and accepting requests. */
  async ping(): Promise<boolean> {
    const resp = await this.request('/ping');
    if (!resp.ok) return false;
    const json = await resp.json() as { pong?: boolean };
    return json.pong === true;
  }

  /** Get a value by key. Returns null when the key does not exist or has expired. */
  async get(key: string, namespace?: string): Promise<DittoGetResult | null> {
    this.validateCoreInputs('get', key, namespace);
    const resp = await this.request(`/key/${encodeURIComponent(key)}`, {
      headers: this.namespaceHeaders(namespace),
    });
    if (resp.status === 404) return null;
    await this.assertOk(resp);
    const body = await resp.json() as { value?: string; value_base64?: string; version: number };
    const value = body.value_base64
      ? Buffer.from(body.value_base64, 'base64')
      : Buffer.from(body.value ?? '', 'utf8');
    return { value, version: body.version };
  }

  /** Set a value. ttlSecs = 0 or omitted means no expiry. */
  async set(key: string, value: string, ttlSecs?: number, namespace?: string): Promise<DittoSetResult> {
    this.validateCoreInputs('set', key, namespace);
    const url  = ttlSecs && ttlSecs > 0
      ? `/key/${encodeURIComponent(key)}?ttl=${ttlSecs}`
      : `/key/${encodeURIComponent(key)}`;
    const resp = await this.request(url, {
      method:  'PUT',
      headers: {
        'Content-Type': 'text/plain',
        ...(this.namespaceHeaders(namespace) ?? {}),
      },
      body:    value,
    });
    await this.assertOk(resp);
    const body = await resp.json() as { version: number };
    return { version: body.version };
  }

  async setNX(
    key: string,
    value: string | Buffer,
    ttlSecs: number,
    namespace?: string,
  ): Promise<DittoSetNxResult> {
    this.validateCoreInputs('setNX', key, namespace);
    const url = `/key/${encodeURIComponent(key)}?nx=1${ttlSecs > 0 ? `&ttl=${ttlSecs}` : ''}`;
    const resp = await this.request(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/octet-stream',
        ...(this.namespaceHeaders(namespace) ?? {}),
      },
      body: typeof value === 'string' ? Buffer.from(value, 'utf8') : value,
    });
    if ([400, 404, 501].includes(resp.status)) {
      throw await unsupportedAtomicHttpError(resp, 'SET_NX');
    }
    await this.assertOk(resp);
    const body = await resp.json() as { created: boolean; version: string };
    return { created: body.created, version: BigInt(body.version) };
  }

  async incr(
    key: string,
    opts?: { delta?: bigint | number; ttlSecsOnCreate?: number; namespace?: string },
  ): Promise<DittoCounterResult> {
    this.validateCoreInputs('incr', key, opts?.namespace);
    const payload: { delta?: string; ttl_secs_on_create?: number } = {};
    if (opts?.delta !== undefined) {
      payload.delta = opts.delta.toString();
    }
    if (opts?.ttlSecsOnCreate !== undefined && opts.ttlSecsOnCreate > 0) {
      payload.ttl_secs_on_create = opts.ttlSecsOnCreate;
    }
    const resp = await this.request(`/key/${encodeURIComponent(key)}/incr`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(this.namespaceHeaders(opts?.namespace) ?? {}),
      },
      body: JSON.stringify(payload),
    });
    if ([400, 404, 501].includes(resp.status)) {
      throw await unsupportedAtomicHttpError(resp, 'INCR');
    }
    await this.assertOk(resp);
    const body = await resp.json() as { value: string; version: string };
    return { value: BigInt(body.value), version: BigInt(body.version) };
  }

  /** Delete a key. Returns true if the key existed, false if not found. */
  async delete(key: string, namespace?: string): Promise<boolean> {
    this.validateCoreInputs('delete', key, namespace);
    const resp = await this.request(`/key/${encodeURIComponent(key)}`, {
      method: 'DELETE',
      headers: this.namespaceHeaders(namespace),
    });
    if (resp.status === 404 || resp.status === 204) return resp.status === 204;
    await this.assertOk(resp);
    return true;
  }

  /** Delete all keys matching a glob-style pattern ('*' wildcard). */
  async deleteByPattern(pattern: string, namespace?: string): Promise<DittoDeleteByPatternResult> {
    this.validatePatternInputs('deleteByPattern', pattern, namespace);
    const resp = await this.request('/keys/delete-by-pattern', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(this.namespaceHeaders(namespace) ?? {}),
      },
      body: JSON.stringify({ pattern }),
    });
    await this.assertOk(resp);
    const body = await resp.json() as { deleted: number };
    return { deleted: body.deleted };
  }

  /**
   * Update TTL for all keys matching a glob-style pattern ('*' wildcard).
   * ttlSecs <= 0 or omitted removes TTL from matched keys.
   */
  async setTtlByPattern(pattern: string, ttlSecs?: number, namespace?: string): Promise<DittoSetTtlByPatternResult> {
    this.validatePatternInputs('setTtlByPattern', pattern, namespace);
    const payload: { pattern: string; ttl_secs?: number } = { pattern };
    if (ttlSecs !== undefined && ttlSecs > 0) {
      payload.ttl_secs = ttlSecs;
    }
    const resp = await this.request('/keys/ttl-by-pattern', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(this.namespaceHeaders(namespace) ?? {}),
      },
      body: JSON.stringify(payload),
    });
    await this.assertOk(resp);
    const body = await resp.json() as { updated: number };
    return { updated: body.updated };
  }

  /** Return cache statistics for this node. Available on HTTP client only. */
  async stats(): Promise<DittoStatsResult> {
    const resp = await this.request('/stats');
    await this.assertOk(resp);
    return resp.json() as Promise<DittoStatsResult>;
  }

}

async function unsupportedAtomicHttpError(
  resp: Response,
  operation: 'SET_NX' | 'INCR',
): Promise<Error> {
  try {
    const body = await resp.json() as { error?: string; message?: string };
    if (body.error === 'UnsupportedRequest') {
      return new DittoError('UnsupportedRequest', body.message ?? 'UnsupportedRequest');
    }
  } catch {
    // fall through to normalized message below
  }
  return new DittoError(
    'UnsupportedRequest',
    `UnsupportedRequest: server does not support ${operation}. Upgrade dittod to a version with atomic primitives.`,
  );
}
