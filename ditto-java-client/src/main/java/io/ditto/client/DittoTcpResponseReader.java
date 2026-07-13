package io.ditto.client;

import java.io.DataInputStream;
import java.io.IOException;

final class DittoTcpResponseReader {

    private final int maxFrameBytes;
    private final DittoTcpWireResponseDecoder responseDecoder;

    DittoTcpResponseReader(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
        this.responseDecoder = new DittoTcpWireResponseDecoder();
    }

    DittoTcpClient.Response readResponse(DataInputStream in) throws IOException {
        int payloadLen = in.readInt();
        if (payloadLen <= 0 || payloadLen > maxFrameBytes) {
            throw new IOException(
                    "Incoming frame has invalid size: " + payloadLen + " (limit " + maxFrameBytes + ")"
            );
        }
        byte[] payload = new byte[payloadLen];
        in.readFully(payload);
        return responseDecoder.decodeResponse(payload);
    }
}
