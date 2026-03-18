/**
 * DittoHttpClient – generated from api/ditto-http-api.yaml v1.1.0
 *
 * DO NOT EDIT MANUALLY.
 * Regenerate with: cd src/tools && npm run generate
 */

import { DittoHttpClientBase } from './http-client-base.js';
import type { DittoGetResult, DittoSetResult, DittoStatsResult } from './types.js';

export class DittoHttpClient extends DittoHttpClientBase {

  // ── Generated endpoint methods (from api/ditto-http-api.yaml) ─────────────

  /** Check whether the node is alive and accepting requests. */
  async ping(): Promise<boolean> {
    const resp = await this.request('/ping');
    if (!resp.ok) return false;
    const json = await resp.json() as { pong?: boolean };
    return json.pong === true;
  }

  /** Get a value by key. Returns null when the key does not exist or has expired. */
  async get(key: string): Promise<DittoGetResult | null> {
    const resp = await this.request(`/key/${encodeURIComponent(key)}`);
    if (resp.status === 404) return null;
    await this.assertOk(resp);
    const body = await resp.json() as { value: string; version: number };
    return { value: Buffer.from(body.value, 'utf8'), version: body.version };
  }

  /** Set a value. ttlSecs = 0 or omitted means no expiry. */
  async set(key: string, value: string, ttlSecs?: number): Promise<DittoSetResult> {
    const url  = ttlSecs && ttlSecs > 0
      ? `/key/${encodeURIComponent(key)}?ttl=${ttlSecs}`
      : `/key/${encodeURIComponent(key)}`;
    const resp = await this.request(url, {
      method:  'PUT',
      headers: { 'Content-Type': 'text/plain' },
      body:    value,
    });
    await this.assertOk(resp);
    const body = await resp.json() as { version: number };
    return { version: body.version };
  }

  /** Delete a key. Returns true if the key existed, false if not found. */
  async delete(key: string): Promise<boolean> {
    const resp = await this.request(`/key/${encodeURIComponent(key)}`, { method: 'DELETE' });
    if (resp.status === 404 || resp.status === 204) return resp.status === 204;
    await this.assertOk(resp);
    return true;
  }

  /** Return cache statistics for this node. Available on HTTP client only. */
  async stats(): Promise<DittoStatsResult> {
    const resp = await this.request('/stats');
    await this.assertOk(resp);
    return resp.json() as Promise<DittoStatsResult>;
  }

}
