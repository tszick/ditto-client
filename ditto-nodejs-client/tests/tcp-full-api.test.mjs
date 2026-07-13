import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import * as tls from 'node:tls';

import { DittoTcpClient } from '../dist/index.js';
import {
  REQUEST_FIELDS,
  RESPONSE_FIELDS,
  decodeClientRequestVariant,
  encodeCounterResponseInner,
  encodeSetNxResponseInner,
  frameClientResponse,
  encodeVersionResponseInner,
} from '../dist/wire.js';

const TEST_CERT_PEM = `-----BEGIN CERTIFICATE-----
MIIC5jCCAc6gAwIBAgIBATANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDEwkxMjcu
MC4wLjEwHhcNMjYwNzEzMDgwNDI2WhcNMjYwNzE0MDkwNDI2WjAUMRIwEAYDVQQD
EwkxMjcuMC4wLjEwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC5Hc/T
72VvvrgR436eYOmPSsUjlfnB3vvnKax1Nw+nhlBYPmVJn/iNvXDulJ0HqFWLFHN1
cJysrebCDRcoFZscliEwYu7zuh3MbqTissvydiMjRK+OjrqFicvIWci3XG/oN5kN
r2gFGXZJ9hrUpjtNIBUIU9M2putkbFemRuV9czDqVTsTQWj9FefMzhOdfatcnPkc
ORCJe9cQuKynHe3Qc21b6nKSMg9RzH+bTwGFotcBlpsLWkcVM9Jodb5yL9RsRuV4
YhqsNOkaXwO0q9PmSzehkbvu2odXz/TFi7HI08RuU97dC3RsPQwVGn44l17fnZRm
RHPbxjSh4/0OXmdHAgMBAAGjQzBBMA4GA1UdDwEB/wQEAwIFoDATBgNVHSUEDDAK
BggrBgEFBQcDATAaBgNVHREEEzARgglsb2NhbGhvc3SHBH8AAAEwDQYJKoZIhvcN
AQELBQADggEBAJSpGSTK81AziK4EK5vpd1WoaKLb4G+OZsF8UwiHR/u8GR2jr4MC
V54N5FQXpINB9OABIKtBxTfrNiXWebCpLLSj42xg1qBw1D3AZ58lMqzqBR5D6ZWg
kWk29NNu2Kw9WHJvLkifCHQK6wmKEcaIfAmy+1xn0IaYiM6X+ELVWj34ri9Ja4d1
yNLBLtmrFy2LuGdoQjqB3pUKgcX6wZ6ogKmpAjpAG1phqnRCqstWEASaMqJ5xv+Z
QA16rmRMPOiirSi4e0/WUQqH1nF+6Qyi5nQLQpZPh9ds9pEnCuB6ECrXFFQPjL2y
GnjWYu2HHO1qQR7nTZ+e9TQ0sZIM4LV5qIE=
-----END CERTIFICATE-----`;

const TEST_KEY_PEM = `-----BEGIN RSA PRIVATE KEY-----
MIIEpQIBAAKCAQEAuR3P0+9lb764EeN+nmDpj0rFI5X5wd775ymsdTcPp4ZQWD5l
SZ/4jb1w7pSdB6hVixRzdXCcrK3mwg0XKBWbHJYhMGLu87odzG6k4rLL8nYjI0Sv
jo66hYnLyFnIt1xv6DeZDa9oBRl2SfYa1KY7TSAVCFPTNqbrZGxXpkblfXMw6lU7
E0Fo/RXnzM4TnX2rXJz5HDkQiXvXELispx3t0HNtW+pykjIPUcx/m08BhaLXAZab
C1pHFTPSaHW+ci/UbEbleGIarDTpGl8DtKvT5ks3oZG77tqHV8/0xYuxyNPEblPe
3Qt0bD0MFRp+OJde352UZkRz28Y0oeP9Dl5nRwIDAQABAoIBAA9jFnzX19cng7Zc
8g/pH1DdVrCkDTwbtFWdJawilQcIR5JmMVYi2W6ysfnq2Xii+eVTIFvBLgy+ccFs
hCG9VgTUx9J1TsZskICHK+Z6FTDEuBv84BjZ7VAfSZSQPfpb0SN8x5iXHW7bFHWG
YumNHb3F7mmgShyvWD6jMM/t8bJxJe8rgpNMSu/yA/VsfpEy9fOiBtJ6Jo4o+kPZ
lEZShe0st21KJigN0JwhoA8bQpWyKpFtQAcqRN9Mlc780ODRMrZXrs1jQ8at7KSN
uC7tReTNPUg0j9ql7Ey6J8qe0UtWea+BF8RRkG+1bmdnQZmqa1qShiuK5ZnAWMal
CIhpZFkCgYEAyMGuHI150XYsgBd4v8I94eYlc5CkwClTTbULe2ghzi1i9Z/0o+9E
Dn/9NQWxidqC69SGCx+vBbm7RUV14ubrlNRwVq/VAqsIqBTnhwpbRY/vqPNWNgIx
mPNFRLlTnC5nKwqbDWixTwjfHl++1ctG42+yMIHJWJsMKg5o5sW63Z8CgYEA7A5c
vVkSLxJSXuxdk1ATNL7owYJqswBiCYrdqS3vfnYtpW+FVFkyiUrzBXgEciYSoWQa
bt1AV5UQaFA/tI5jW2ljspDoNR65Am0kXeygFA4rJkkz4kIC82P63I/GFLP57sFS
K6PlAF4WF8ae7gFeQCGQC8CG/T18kmOmXDakxVkCgYEArRR+PdOjcPkHSK/zxK98
lqPLKiVMRPfcACTUb2LJsm3i4Y00Z5nC/RVPgkUUWZtwQE4L+s8oIDGOyRwnlKYt
+TRmXfZeGVzHq9HKAtzk78Y2g1y3uPyPMiSaVbPJ598Bx1PvddIK++7UHeXCK6SD
y1XjNHrQ0nlqNWATBNL4VlUCgYEAqouR20dgCNwu4N/al5Tx21jWpwBHgH4VVpma
niFO98oAHpdc99zd0y1wORJF/AafzTSamGCHnP9YdFUOQa/h/ug8nIVvDvncZvFd
pfJQkUzPRgD7WEujAB/K3dGOJeUF/MZ1TIxD5ikTwyfAKWqZorHc9XCq1om217jh
N5xPHTkCgYEAvfMPDvvsysu1nvL6wQMG5/5Iu4s7/EzDQFLBJ7Eq8fp4UGZ1i12d
CTzc/gn1Sf6aOlUnujUc7hVSr9DMt/4rIs/9z4BX3yEJbsuKKVXQUr4P38uHYL9V
STR6d5nV/3BCZT0NTgw74oMe1ZGGEmVIg1Pki/6yQ4jcjVg4QnoqUD4=
-----END RSA PRIVATE KEY-----`;

function readVariant(socket) {
  return new Promise((resolve, reject) => {
    let buf = Buffer.alloc(0);
    const onData = (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (buf.length < 4) return;
      const len = buf.readUInt32BE(0);
      if (buf.length < 4 + len) return;
      socket.off('data', onData);
      try {
        resolve(decodeClientRequestVariant(buf.subarray(4, 4 + len)).field);
      } catch (err) {
        reject(err);
      }
    };
    socket.on('data', onData);
    socket.once('error', reject);
  });
}

test('tcp client exercises auth get delete pattern and ttl commands', async () => {
  const expected = [
    REQUEST_FIELDS.AUTH,
    REQUEST_FIELDS.SET,
    REQUEST_FIELDS.GET,
    REQUEST_FIELDS.DELETE,
    REQUEST_FIELDS.DELETE_BY_PATTERN,
    REQUEST_FIELDS.SET_TTL_BY_PATTERN,
    REQUEST_FIELDS.SET_NX,
    REQUEST_FIELDS.INCR,
  ];
  const responses = new Map([
    [REQUEST_FIELDS.AUTH, frameClientResponse(RESPONSE_FIELDS.AUTH_OK, Buffer.alloc(0))],
    [REQUEST_FIELDS.SET, frameClientResponse(RESPONSE_FIELDS.OK, encodeVersionResponseInner(7))],
    [REQUEST_FIELDS.GET, frameClientResponse(RESPONSE_FIELDS.VALUE, Buffer.alloc(0))],
    [REQUEST_FIELDS.DELETE, frameClientResponse(RESPONSE_FIELDS.DELETED, Buffer.alloc(0))],
    [REQUEST_FIELDS.DELETE_BY_PATTERN, frameClientResponse(RESPONSE_FIELDS.PATTERN_DELETED, Buffer.alloc(0))],
    [REQUEST_FIELDS.SET_TTL_BY_PATTERN, frameClientResponse(RESPONSE_FIELDS.PATTERN_TTL_UPDATED, Buffer.alloc(0))],
    [REQUEST_FIELDS.SET_NX, frameClientResponse(RESPONSE_FIELDS.SET_NX, encodeSetNxResponseInner(true, 9n))],
    [REQUEST_FIELDS.INCR, frameClientResponse(RESPONSE_FIELDS.COUNTER, encodeCounterResponseInner(11n, 12n))],
  ]);

  const server = net.createServer(async (socket) => {
    try {
      for (const want of expected) {
        const got = await readVariant(socket);
        assert.equal(got, want);
        socket.write(responses.get(got));
      }
    } finally {
      socket.end();
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;

  const client = new DittoTcpClient({
    host: '127.0.0.1',
    port,
    authToken: 'token',
    strictMode: true,
  });
  try {
    await client.connect();
    assert.equal((await client.set('ns-key', 'value', 30, 'tenant-a')).version, 7);
    assert.deepEqual(await client.get('ns-key', 'tenant-a'), { value: Buffer.alloc(0), version: 0 });
    assert.equal(await client.delete('ns-key', 'tenant-a'), true);
    assert.deepEqual(await client.deleteByPattern('tenant:*', 'tenant-a'), { deleted: 0 });
    assert.deepEqual(await client.setTtlByPattern('tenant:*', 45, 'tenant-a'), { updated: 0 });
    assert.deepEqual(await client.setNX('lease-key', Buffer.from([1, 2, 3]), 30, 'tenant-a'), {
      created: true,
      version: 9n,
    });
    assert.deepEqual(await client.incr('counter', { delta: 11n, namespace: 'tenant-a' }), {
      value: 11n,
      version: 12n,
    });
  } finally {
    await client.close();
    await new Promise((resolve) => server.close(resolve));
  }
});

test('tcp client can connect over TLS with CA verification', async () => {
  const server = tls.createServer({ key: TEST_KEY_PEM, cert: TEST_CERT_PEM }, async (socket) => {
    try {
      assert.equal(await readVariant(socket), REQUEST_FIELDS.AUTH);
      socket.write(frameClientResponse(RESPONSE_FIELDS.AUTH_OK, Buffer.alloc(0)));
      assert.equal(await readVariant(socket), REQUEST_FIELDS.PING);
      socket.write(frameClientResponse(RESPONSE_FIELDS.PONG, Buffer.alloc(0)));
    } finally {
      socket.end();
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;

  const client = new DittoTcpClient({
    host: '127.0.0.1',
    port,
    authToken: 'token',
    tls: true,
    tlsCaCert: TEST_CERT_PEM,
    tlsServerName: 'localhost',
  });
  try {
    await client.connect();
    assert.equal(await client.ping(), true);
  } finally {
    await client.close();
    await new Promise((resolve) => server.close(resolve));
  }
});
