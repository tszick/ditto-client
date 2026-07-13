package io.ditto.client;

import java.nio.charset.StandardCharsets;

final class DittoTcpWireRequestEncoder {

    byte[] wrapClientRequest(int variantField, byte[] inner) {
        DittoTcpWireWriter reqWriter = new DittoTcpWireWriter();
        reqWriter.ldFieldAlways(variantField, inner);
        byte[] requestBytes = reqWriter.toByteArray();

        DittoTcpWireWriter envWriter = new DittoTcpWireWriter();
        envWriter.enumField(DittoTcpClient.Wire.ENV_VERSION, DittoTcpClient.Wire.PROTOCOL_VERSION);
        envWriter.ldFieldAlways(DittoTcpClient.Wire.ENV_CLIENT_REQUEST, requestBytes);
        byte[] envelope = envWriter.toByteArray();

        byte[] out = new byte[4 + envelope.length];
        out[0] = (byte) ((envelope.length >>> 24) & 0xFF);
        out[1] = (byte) ((envelope.length >>> 16) & 0xFF);
        out[2] = (byte) ((envelope.length >>> 8) & 0xFF);
        out[3] = (byte) (envelope.length & 0xFF);
        System.arraycopy(envelope, 0, out, 4, envelope.length);
        return out;
    }

    byte[] encodeKeyNamespace(String key, String namespace) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.stringField(DittoTcpClient.Wire.KN_KEY, key);
        if (hasNamespace(namespace)) {
          w.ldField(DittoTcpClient.Wire.KN_NAMESPACE, encodeOptionalString(namespace));
        }
        return w.toByteArray();
    }

    byte[] encodePatternNamespace(String pattern, String namespace) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.stringField(DittoTcpClient.Wire.PN_PATTERN, pattern);
        if (hasNamespace(namespace)) {
          w.ldField(DittoTcpClient.Wire.PN_NAMESPACE, encodeOptionalString(namespace));
        }
        return w.toByteArray();
    }

    byte[] encodeSetRequest(String key, byte[] value, long ttlSecs, String namespace) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.stringField(DittoTcpClient.Wire.SR_KEY, key);
        w.bytesField(DittoTcpClient.Wire.SR_VALUE, value);
        if (ttlSecs > 0) {
            w.ldField(DittoTcpClient.Wire.SR_TTL_SECS, encodeOptionalUint64(ttlSecs));
        }
        if (hasNamespace(namespace)) {
            w.ldField(DittoTcpClient.Wire.SR_NAMESPACE, encodeOptionalString(namespace));
        }
        return w.toByteArray();
    }

    byte[] encodeIncrRequest(String key, long delta, long ttlSecsOnCreate, String namespace) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.stringField(DittoTcpClient.Wire.INCR_KEY, key);
        w.int64Field(DittoTcpClient.Wire.INCR_DELTA, delta);
        if (ttlSecsOnCreate > 0) {
            w.ldField(DittoTcpClient.Wire.INCR_TTL_SECS_ON_CREATE, encodeOptionalUint64(ttlSecsOnCreate));
        }
        if (hasNamespace(namespace)) {
            w.ldField(DittoTcpClient.Wire.INCR_NAMESPACE, encodeOptionalString(namespace));
        }
        return w.toByteArray();
    }

    byte[] encodeSetTtlByPatternRequest(String pattern, long ttlSecs, String namespace) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.stringField(DittoTcpClient.Wire.STBP_PATTERN, pattern);
        if (ttlSecs > 0) {
            w.ldField(DittoTcpClient.Wire.STBP_TTL_SECS, encodeOptionalUint64(ttlSecs));
        }
        if (hasNamespace(namespace)) {
            w.ldField(DittoTcpClient.Wire.STBP_NAMESPACE, encodeOptionalString(namespace));
        }
        return w.toByteArray();
    }

    byte[] encodeAuthRequest(String token) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.stringField(DittoTcpClient.Wire.AUTH_TOKEN, token);
        return w.toByteArray();
    }

    private byte[] encodeOptionalString(String value) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.stringField(DittoTcpClient.Wire.OPT_VALUE, value);
        return w.toByteArray();
    }

    private byte[] encodeOptionalUint64(long value) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.uint64Field(DittoTcpClient.Wire.OPT_VALUE, value);
        return w.toByteArray();
    }

    private boolean hasNamespace(String ns) {
        return ns != null && !ns.isBlank();
    }
}
