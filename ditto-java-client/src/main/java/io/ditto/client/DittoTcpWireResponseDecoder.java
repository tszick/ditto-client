package io.ditto.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class DittoTcpWireResponseDecoder {

    DittoTcpClient.Response decodeResponse(byte[] payload) throws IOException {
        DittoTcpWireReader env = new DittoTcpWireReader(payload);
        byte[] responseBytes = null;
        long version = 0;
        while (env.remaining() > 0) {
            int[] t = env.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.ENV_VERSION && wire == DittoTcpClient.Wire.WT_VARINT) {
                version = env.readVarint();
            } else if (field == DittoTcpClient.Wire.ENV_CLIENT_RESPONSE && wire == DittoTcpClient.Wire.WT_LD) {
                responseBytes = env.readLD();
            } else {
                env.skip(wire);
            }
        }
        if (version != 0 && version != DittoTcpClient.Wire.PROTOCOL_VERSION) {
            throw new IOException("unsupported protocol version: " + version);
        }
        if (responseBytes == null) {
            throw new IOException("Envelope is missing client_response payload");
        }

        DittoTcpWireReader r = new DittoTcpWireReader(responseBytes);
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (wire != DittoTcpClient.Wire.WT_LD) {
                r.skip(wire);
                continue;
            }
            byte[] inner = r.readLD();
            DittoTcpClient.Response out = new DittoTcpClient.Response();
            switch (field) {
                case DittoTcpClient.Wire.RESP_VALUE -> {
                    decodeValue(inner, out);
                    out.type = DittoTcpClient.ResponseType.VALUE;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_OK -> {
                    decodeOk(inner, out);
                    out.type = DittoTcpClient.ResponseType.OK;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_DELETED -> {
                    out.type = DittoTcpClient.ResponseType.DELETED;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_NOT_FOUND -> {
                    out.type = DittoTcpClient.ResponseType.NOT_FOUND;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_PONG -> {
                    out.type = DittoTcpClient.ResponseType.PONG;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_AUTH_OK -> {
                    out.type = DittoTcpClient.ResponseType.AUTH_OK;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_ERROR -> {
                    decodeError(inner, out);
                    out.type = DittoTcpClient.ResponseType.ERROR;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_WATCHING -> {
                    out.type = DittoTcpClient.ResponseType.WATCHING;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_UNWATCHED -> {
                    out.type = DittoTcpClient.ResponseType.UNWATCHED;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_WATCH_EVENT -> {
                    decodeWatchEvent(inner, out);
                    out.type = DittoTcpClient.ResponseType.WATCH_EVENT;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_PATTERN_DELETED -> {
                    out.count = decodeCount(inner);
                    out.type = DittoTcpClient.ResponseType.PATTERN_DELETED;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_PATTERN_TTL_UPDATED -> {
                    out.count = decodeCount(inner);
                    out.type = DittoTcpClient.ResponseType.PATTERN_TTL_UPDATED;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_SET_NX -> {
                    decodeSetNx(inner, out);
                    out.type = DittoTcpClient.ResponseType.SET_NX;
                    return out;
                }
                case DittoTcpClient.Wire.RESP_COUNTER -> {
                    decodeCounter(inner, out);
                    out.type = DittoTcpClient.ResponseType.COUNTER;
                    return out;
                }
                default -> {
                }
            }
        }
        throw new IOException("ClientResponse oneof has no active field");
    }

    private void decodeValue(byte[] buf, DittoTcpClient.Response out) throws IOException {
        DittoTcpWireReader r = new DittoTcpWireReader(buf);
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.VAL_KEY && wire == DittoTcpClient.Wire.WT_LD) {
                out.key = new String(r.readLD(), StandardCharsets.UTF_8);
            } else if (field == DittoTcpClient.Wire.VAL_VALUE && wire == DittoTcpClient.Wire.WT_LD) {
                out.value = r.readLD();
            } else if (field == DittoTcpClient.Wire.VAL_VERSION && wire == DittoTcpClient.Wire.WT_VARINT) {
                out.version = r.readVarint();
            } else {
                r.skip(wire);
            }
        }
        if (out.value == null) out.value = new byte[0];
    }

    private void decodeOk(byte[] buf, DittoTcpClient.Response out) throws IOException {
        DittoTcpWireReader r = new DittoTcpWireReader(buf);
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.VR_VERSION && wire == DittoTcpClient.Wire.WT_VARINT) out.version = r.readVarint();
            else r.skip(wire);
        }
    }

    private void decodeSetNx(byte[] buf, DittoTcpClient.Response out) throws IOException {
        DittoTcpWireReader r = new DittoTcpWireReader(buf);
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.SNX_CREATED && wire == DittoTcpClient.Wire.WT_VARINT) out.created = r.readVarint() != 0;
            else if (field == DittoTcpClient.Wire.SNX_VERSION && wire == DittoTcpClient.Wire.WT_VARINT) out.version = r.readVarint();
            else r.skip(wire);
        }
    }

    private void decodeCounter(byte[] buf, DittoTcpClient.Response out) throws IOException {
        DittoTcpWireReader r = new DittoTcpWireReader(buf);
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.CTR_VALUE && wire == DittoTcpClient.Wire.WT_VARINT) out.counterValue = r.readVarint();
            else if (field == DittoTcpClient.Wire.CTR_VERSION && wire == DittoTcpClient.Wire.WT_VARINT) out.version = r.readVarint();
            else r.skip(wire);
        }
    }

    private void decodeError(byte[] buf, DittoTcpClient.Response out) throws IOException {
        DittoTcpWireReader r = new DittoTcpWireReader(buf);
        int codeIdx = 0;
        String message = "";
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.ERR_CODE && wire == DittoTcpClient.Wire.WT_VARINT) codeIdx = r.readVarintAsInt();
            else if (field == DittoTcpClient.Wire.ERR_MESSAGE && wire == DittoTcpClient.Wire.WT_LD) message = new String(r.readLD(), StandardCharsets.UTF_8);
            else r.skip(wire);
        }
        out.errorCode = DittoErrorCode.fromIndex(codeIdx);
        out.message = message;
    }

    private void decodeWatchEvent(byte[] buf, DittoTcpClient.Response out) throws IOException {
        DittoTcpWireReader r = new DittoTcpWireReader(buf);
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.WE_KEY && wire == DittoTcpClient.Wire.WT_LD) out.key = new String(r.readLD(), StandardCharsets.UTF_8);
            else if (field == DittoTcpClient.Wire.WE_VALUE && wire == DittoTcpClient.Wire.WT_LD) {
                out.value = decodeOptionalBytes(r.readLD());
                out.hasValue = true;
            } else if (field == DittoTcpClient.Wire.WE_VERSION && wire == DittoTcpClient.Wire.WT_VARINT) out.version = r.readVarint();
            else r.skip(wire);
        }
    }

    private byte[] decodeOptionalBytes(byte[] buf) throws IOException {
        DittoTcpWireReader r = new DittoTcpWireReader(buf);
        byte[] out = new byte[0];
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.OPT_VALUE && wire == DittoTcpClient.Wire.WT_LD) out = r.readLD();
            else r.skip(wire);
        }
        return out;
    }

    private long decodeCount(byte[] buf) throws IOException {
        DittoTcpWireReader r = new DittoTcpWireReader(buf);
        long count = 0;
        while (r.remaining() > 0) {
            int[] t = r.readTag();
            int field = t[0], wire = t[1];
            if (field == DittoTcpClient.Wire.COUNT_FIELD && wire == DittoTcpClient.Wire.WT_VARINT) count = r.readVarint();
            else r.skip(wire);
        }
        return count;
    }
}
