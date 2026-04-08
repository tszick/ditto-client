package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DittoTcpClientNamespaceEncodingSmokeTest {

    @Test
    void encodeGetWritesNamespaceAsBincodeOptionSome() throws Exception {
        DittoTcpClient client = new DittoTcpClient();

        Method encodeGet = DittoTcpClient.class.getDeclaredMethod("encodeGet", String.class, String.class);
        encodeGet.setAccessible(true);

        byte[] frame = (byte[]) encodeGet.invoke(client, "k", "tenant-a");
        ByteBuffer payload = payload(frame);

        assertEquals(0, payload.getInt());
        int keyLen = (int) payload.getLong();
        payload.position(payload.position() + keyLen);

        byte option = payload.get();
        assertEquals(1, option);

        int nsLen = (int) payload.getLong();
        byte[] ns = new byte[nsLen];
        payload.get(ns);
        assertArrayEquals("tenant-a".getBytes(StandardCharsets.UTF_8), ns);
    }

    @Test
    void encodeGetWritesNamespaceAsBincodeOptionNoneWhenNull() throws Exception {
        DittoTcpClient client = new DittoTcpClient();

        Method encodeGet = DittoTcpClient.class.getDeclaredMethod("encodeGet", String.class, String.class);
        encodeGet.setAccessible(true);

        byte[] frame = (byte[]) encodeGet.invoke(client, "k", null);
        ByteBuffer payload = payload(frame);

        assertEquals(0, payload.getInt());
        int keyLen = (int) payload.getLong();
        payload.position(payload.position() + keyLen);

        assertEquals(0, payload.get());
    }

    private static ByteBuffer payload(byte[] frame) {
        ByteBuffer framed = ByteBuffer.wrap(frame);
        int payloadLen = framed.getInt();

        byte[] payload = new byte[payloadLen];
        framed.get(payload);
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }
}
