package io.ditto.client;

final class DittoTcpRequestFactory {
    private final DittoTcpWireRequestEncoder encoder = new DittoTcpWireRequestEncoder();

    byte[] encodeGet(String key, String namespace) {
        return encoder.wrapClientRequest(
                DittoTcpClient.Wire.REQ_GET,
                encoder.encodeKeyNamespace(key, namespace)
        );
    }

    byte[] encodeSet(String key, byte[] value, long ttlSecs, String namespace) {
        return encoder.wrapClientRequest(
                DittoTcpClient.Wire.REQ_SET,
                encoder.encodeSetRequest(key, value, ttlSecs, namespace)
        );
    }

    byte[] encodeDelete(String key, String namespace) {
        return encoder.wrapClientRequest(
                DittoTcpClient.Wire.REQ_DELETE,
                encoder.encodeKeyNamespace(key, namespace)
        );
    }

    byte[] encodeSetNx(String key, byte[] value, long ttlSecs, String namespace) {
        return encoder.wrapClientRequest(
                DittoTcpClient.Wire.REQ_SET_NX,
                encoder.encodeSetRequest(key, value, ttlSecs, namespace)
        );
    }

    byte[] encodeIncr(String key, long delta, long ttlSecsOnCreate, String namespace) {
        return encoder.wrapClientRequest(
                DittoTcpClient.Wire.REQ_INCR,
                encoder.encodeIncrRequest(key, delta, ttlSecsOnCreate, namespace)
        );
    }

    byte[] encodePing() {
        return encoder.wrapClientRequest(DittoTcpClient.Wire.REQ_PING, new byte[0]);
    }

    byte[] encodeAuth(String token) {
        return encoder.wrapClientRequest(
                DittoTcpClient.Wire.REQ_AUTH,
                encoder.encodeAuthRequest(token)
        );
    }

    byte[] encodeWatch(String key, String namespace) {
        return encoder.wrapClientRequest(
                DittoTcpClient.Wire.REQ_WATCH,
                encoder.encodeKeyNamespace(key, namespace)
        );
    }

    byte[] encodeUnwatch(String key, String namespace) {
        return encoder.wrapClientRequest(
                DittoTcpClient.Wire.REQ_UNWATCH,
                encoder.encodeKeyNamespace(key, namespace)
        );
    }

    byte[] encodeDeleteByPattern(String pattern, String namespace) {
        return encoder.wrapClientRequest(
                DittoTcpClient.Wire.REQ_DELETE_BY_PATTERN,
                encoder.encodePatternNamespace(pattern, namespace)
        );
    }

    byte[] encodeSetTtlByPattern(String pattern, long ttlSecs, String namespace) {
        return encoder.wrapClientRequest(
                DittoTcpClient.Wire.REQ_SET_TTL_BY_PATTERN,
                encoder.encodeSetTtlByPatternRequest(pattern, ttlSecs, namespace)
        );
    }
}
