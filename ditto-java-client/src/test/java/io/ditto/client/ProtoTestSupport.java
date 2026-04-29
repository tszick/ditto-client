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
        DittoTcpClient.Wire.Writer respWriter = new DittoTcpClient.Wire.Writer();
        respWriter.ldFieldAlways(variantField, inner);
        byte[] responseBytes = respWriter.toByteArray();

        DittoTcpClient.Wire.Writer envWriter = new DittoTcpClient.Wire.Writer();
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
        DittoTcpClient.Wire.Writer w = new DittoTcpClient.Wire.Writer();
        w.uint64Field(DittoTcpClient.Wire.VR_VERSION, version);
        return w.toByteArray();
    }

    static byte[] encodeWatchEventInner(String key, byte[] value, boolean hasValue, long version) {
        DittoTcpClient.Wire.Writer w = new DittoTcpClient.Wire.Writer();
        w.stringField(DittoTcpClient.Wire.WE_KEY, key);
        if (hasValue) {
            DittoTcpClient.Wire.Writer optBytes = new DittoTcpClient.Wire.Writer();
            optBytes.bytesField(DittoTcpClient.Wire.OPT_VALUE, value);
            w.ldFieldAlways(DittoTcpClient.Wire.WE_VALUE, optBytes.toByteArray());
        }
        w.uint64Field(DittoTcpClient.Wire.WE_VERSION, version);
        return w.toByteArray();
    }

    /** Parse an Envelope payload (no length prefix) and return the active
     *  ClientRequest oneof field number. */
    static int readClientRequestVariant(byte[] payload) throws IOException {
        DittoTcpClient.Wire.Reader env = new DittoTcpClient.Wire.Reader(payload);
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

        DittoTcpClient.Wire.Reader r = new DittoTcpClient.Wire.Reader(requestBytes);
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
