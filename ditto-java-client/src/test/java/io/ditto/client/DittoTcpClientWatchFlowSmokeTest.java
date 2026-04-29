package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DittoTcpClientWatchFlowSmokeTest {

    @Test
    void watchSetEventUnwatchFlow() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            Thread t = new Thread(() -> {
                try (Socket conn = server.accept()) {
                    DataInputStream in = new DataInputStream(conn.getInputStream());
                    DataOutputStream out = new DataOutputStream(conn.getOutputStream());

                    assertEquals(DittoTcpClient.Wire.REQ_WATCH, readRequestVariant(in));
                    writeFramedResponse(out, DittoTcpClient.Wire.RESP_WATCHING, new byte[0]);

                    assertEquals(DittoTcpClient.Wire.REQ_SET, readRequestVariant(in));
                    writeFramedResponse(out, DittoTcpClient.Wire.RESP_OK,
                            ProtoTestSupport.encodeVersionResponseInner(1));
                    writeFramedResponse(out, DittoTcpClient.Wire.RESP_WATCH_EVENT,
                            ProtoTestSupport.encodeWatchEventInner("k", "value".getBytes(StandardCharsets.UTF_8), true, 2));

                    assertEquals(DittoTcpClient.Wire.REQ_UNWATCH, readRequestVariant(in));
                    writeFramedResponse(out, DittoTcpClient.Wire.RESP_UNWATCHED, new byte[0]);
                } catch (Throwable e) {
                    serverError.set(e);
                }
            });
            t.start();

            try (DittoTcpClient client = new DittoTcpClient("127.0.0.1", port)) {
                client.connect();
                client.watch("k");
                DittoSetResult set = client.set("k", "value");
                assertEquals(1, set.getVersion());
                DittoWatchEvent event = client.waitForWatchEvent();
                assertEquals("k", event.key());
                assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), event.value());
                assertEquals(2, event.version());
                client.unwatch("k");
            }

            t.join(2000);
            if (serverError.get() != null) {
                throw new AssertionError(serverError.get());
            }
        }
    }

    private static int readRequestVariant(DataInputStream in) throws Exception {
        int payloadLen = in.readInt();
        byte[] payload = in.readNBytes(payloadLen);
        return ProtoTestSupport.readClientRequestVariant(payload);
    }

    private static void writeFramedResponse(DataOutputStream out, int variantField, byte[] inner) throws Exception {
        out.write(ProtoTestSupport.frameClientResponse(variantField, inner));
        out.flush();
    }
}
