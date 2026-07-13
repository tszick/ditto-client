package io.ditto.client;

import java.io.IOException;

/**
 * Shared protobuf-wire test helpers for forging server-style frames and
 * inspecting client-emitted requests.
 *
 * Mirrors the test-only helpers from the cache-side ditto-protocol crate so
 * Java mock servers in tests can encode {@code ClientResponse} variants and
 * decode {@code ClientRequest} variants without depending on a real protobuf
 * runtime.
 */
final class ProtoTestSupport {

    private ProtoTestSupport() {}

    /** Wrap an inner ClientResponse oneof variant in Envelope + 4-byte BE length frame. */
    static byte[] frameClientResponse(int variantField, byte[] inner) {
        DittoTcpWireWriter respWriter = new DittoTcpWireWriter();
        respWriter.ldFieldAlways(variantField, inner);
        byte[] responseBytes = respWriter.toByteArray();

        DittoTcpWireWriter envWriter = new DittoTcpWireWriter();
        envWriter.enumField(DittoTcpClient.Wire.ENV_VERSION, DittoTcpClient.Wire.PROTOCOL_VERSION);
        envWriter.ldFieldAlways(DittoTcpClient.Wire.ENV_CLIENT_RESPONSE, responseBytes);
        byte[] envelope = envWriter.toByteArray();

        byte[] out = new byte[4 + envelope.length];
        out[0] = (byte) ((envelope.length >>> 24) & 0xFF);
        out[1] = (byte) ((envelope.length >>> 16) & 0xFF);
        out[2] = (byte) ((envelope.length >>> 8)  & 0xFF);
        out[3] = (byte) (envelope.length & 0xFF);
        System.arraycopy(envelope, 0, out, 4, envelope.length);
        return out;
    }

    static byte[] encodeVersionResponseInner(long version) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.uint64Field(DittoTcpClient.Wire.VR_VERSION, version);
        return w.toByteArray();
    }

    static byte[] encodeErrorInner(int codeIdx, String message) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.uint64Field(DittoTcpClient.Wire.ERR_CODE, codeIdx);
        w.stringField(DittoTcpClient.Wire.ERR_MESSAGE, message);
        return w.toByteArray();
    }

    static byte[] encodeSetNxInner(boolean created, long version) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.uint64Field(DittoTcpClient.Wire.SNX_CREATED, created ? 1 : 0);
        w.uint64Field(DittoTcpClient.Wire.SNX_VERSION, version);
        return w.toByteArray();
    }

    static byte[] encodeCounterInner(long value, long version) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.int64Field(DittoTcpClient.Wire.CTR_VALUE, value);
        w.uint64Field(DittoTcpClient.Wire.CTR_VERSION, version);
        return w.toByteArray();
    }

    static byte[] encodeWatchEventInner(String key, byte[] value, boolean hasValue, long version) {
        DittoTcpWireWriter w = new DittoTcpWireWriter();
        w.stringField(DittoTcpClient.Wire.WE_KEY, key);
        if (hasValue) {
            DittoTcpWireWriter optBytes = new DittoTcpWireWriter();
            optBytes.bytesField(DittoTcpClient.Wire.OPT_VALUE, value);
            w.ldFieldAlways(DittoTcpClient.Wire.WE_VALUE, optBytes.toByteArray());
        }
        w.uint64Field(DittoTcpClient.Wire.WE_VERSION, version);
        return w.toByteArray();
    }

    /** Parse an Envelope payload (no length prefix) and return the active
     *  ClientRequest oneof field number. */
    static int readClientRequestVariant(byte[] payload) throws IOException {
        DittoTcpWireReader env = new DittoTcpWireReader(payload);
        byte[] requestBytes = null;
        while (env.remaining() > 0) {
            int[] t = env.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.ENV_CLIENT_REQUEST && wire == DittoTcpClient.Wire.WT_LD) {
                requestBytes = env.readLD();
            } else {
                env.skip(wire);
            }
        }
        if (requestBytes == null) throw new IOException("Envelope is missing client_request payload");

        DittoTcpWireReader r = new DittoTcpWireReader(requestBytes);
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (wire != DittoTcpClient.Wire.WT_LD) {
                r.skip(wire);
                continue;
            }
            r.readLD();
            return field;
        }
        throw new IOException("ClientRequest oneof has no active field");
    }
}
