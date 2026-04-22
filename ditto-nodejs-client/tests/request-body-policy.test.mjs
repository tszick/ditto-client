import test from 'node:test';
import assert from 'node:assert/strict';

import { DittoHttpClientBase } from '../dist/http-client-base.js';

class TestHttpClient extends DittoHttpClientBase {
  async sendBody(body) {
    return this.request('/ping', {
      method: 'PUT',
      body,
    });
  }
}

test('http client rejects non-string request bodies before network send', async () => {
  const client = new TestHttpClient({ host: '127.0.0.1', port: 65535 });
  await assert.rejects(
    client.sendBody(Buffer.from('secret')),
    /Unsupported HTTP request body type/,
  );
});

test('http client rejects devInsecureTls bypass', async () => {
  assert.throws(
    () => new TestHttpClient({
      host: '127.0.0.1',
      port: 443,
      tls: true,
      devInsecureTls: true,
    }),
    /devInsecureTls\(true\) is insecure and is no longer supported/,
  );
});
