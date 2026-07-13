package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: confirms that {@code DittoTcpRequestFactory.encodeGet} produces a protobuf-encoded
 * Envelope whose ClientRequest.get → KeyNamespace.namespace field is
 * present (Some) when a namespace is supplied and absent (None) otherwise.
 */
class DittoTcpClientNamespaceEncodingSmokeTest {

    @Test
    void encodeGetWritesNamespaceAsOptionalStringWhenSet() throws Exception {
        DittoTcpRequestFactory requestFactory = new DittoTcpRequestFactory();
        byte[] frame = requestFactory.encodeGet("k", "tenant-a");
        ParsedRequest req = parseRequestFrame(frame);

        assertEquals(DittoTcpClient.Wire.REQ_GET, req.variantField);

        // The KeyNamespace inner message must contain key="k" + namespace OptionalString { value="tenant-a" }.
        InnerKeyNamespace kn = parseKeyNamespace(req.inner);
        assertArrayEquals("k".getBytes(StandardCharsets.UTF_8), kn.key);
        assertEquals(true, kn.namespacePresent);
        assertArrayEquals("tenant-a".getBytes(StandardCharsets.UTF_8), kn.namespaceValue);
    }

    @Test
    void encodeGetOmitsNamespaceWhenNull() throws Exception {
        DittoTcpRequestFactory requestFactory = new DittoTcpRequestFactory();
        byte[] frame = requestFactory.encodeGet("k", null);
        ParsedRequest req = parseRequestFrame(frame);

        assertEquals(DittoTcpClient.Wire.REQ_GET, req.variantField);
        InnerKeyNamespace kn = parseKeyNamespace(req.inner);
        assertArrayEquals("k".getBytes(StandardCharsets.UTF_8), kn.key);
        assertEquals(false, kn.namespacePresent);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static final class ParsedRequest {
        int variantField;
        byte[] inner;
    }

    private static final class InnerKeyNamespace {
        byte[] key;
        boolean namespacePresent;
        byte[] namespaceValue;
    }

    /**
     * Strip the 4-byte big-endian length frame, then walk the Envelope to find
     * the ClientRequest payload, then walk the ClientRequest oneof to find the
     * active variant field number and its inner message bytes.
     */
    private static ParsedRequest parseRequestFrame(byte[] frame) throws Exception {
        // 4-byte BE length prefix.
        int length = ((frame[0] & 0xFF) << 24)
                   | ((frame[1] & 0xFF) << 16)
                   | ((frame[2] & 0xFF) << 8)
                   |  (frame[3] & 0xFF);
        assertTrue(length > 0 && length <= frame.length - 4, "frame length sanity");
        byte[] envelope = new byte[length];
        System.arraycopy(frame, 4, envelope, 0, length);

        DittoTcpWireReader env = new DittoTcpWireReader(envelope);
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
        assertTrue(requestBytes != null, "envelope had a client_request payload");

        DittoTcpWireReader r = new DittoTcpWireReader(requestBytes);
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (wire != DittoTcpClient.Wire.WT_LD) { r.skip(wire); continue; }
            ParsedRequest out = new ParsedRequest();
            out.variantField = field;
            out.inner = r.readLD();
            return out;
        }
        throw new AssertionError("ClientRequest oneof had no active field");
    }

    private static InnerKeyNamespace parseKeyNamespace(byte[] buf) throws Exception {
        DittoTcpWireReader r = new DittoTcpWireReader(buf);
        InnerKeyNamespace out = new InnerKeyNamespace();
        out.key = new byte[0];
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.KN_KEY && wire == DittoTcpClient.Wire.WT_LD) {
                out.key = r.readLD();
            } else if (field == DittoTcpClient.Wire.KN_NAMESPACE && wire == DittoTcpClient.Wire.WT_LD) {
                out.namespacePresent = true;
                byte[] optBytes = r.readLD();
                out.namespaceValue = parseOptionalString(optBytes);
            } else {
                r.skip(wire);
            }
        }
        return out;
    }

    private static byte[] parseOptionalString(byte[] buf) throws Exception {
        DittoTcpWireReader r = new DittoTcpWireReader(buf);
        byte[] out = new byte[0];
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.OPT_VALUE && wire == DittoTcpClient.Wire.WT_LD) {
                out = r.readLD();
            } else {
                r.skip(wire);
            }
        }
        return out;
    }
}
