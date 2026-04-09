package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

                    assertEquals(5, readVariant(in));
                    writeSimple(out, 7);

                    assertEquals(1, readVariant(in));
                    writeOk(out, 1);
                    writeWatchEvent(out, "k", "value".getBytes(StandardCharsets.UTF_8), 2);

                    assertEquals(6, readVariant(in));
                    writeSimple(out, 8);
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

    private static int readVariant(DataInputStream in) throws Exception {
        int payloadLen = in.readInt();
        byte[] payload = in.readNBytes(payloadLen);
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void writeSimple(DataOutputStream out, int variant) throws Exception {
        ByteBuffer payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(variant);
        writePayload(out, payload.array());
    }

    private static void writeOk(DataOutputStream out, long version) throws Exception {
        ByteBuffer payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(1);
        payload.putLong(version);
        writePayload(out, payload.array());
    }

    private static void writeWatchEvent(DataOutputStream out, String key, byte[] value, long version) throws Exception {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(4 + 8 + keyBytes.length + 1 + 8 + value.length + 8)
                .order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(9);
        payload.putLong(keyBytes.length);
        payload.put(keyBytes);
        payload.put((byte) 1);
        payload.putLong(value.length);
        payload.put(value);
        payload.putLong(version);
        writePayload(out, payload.array());
    }

    private static void writePayload(DataOutputStream out, byte[] payload) throws Exception {
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }
}
