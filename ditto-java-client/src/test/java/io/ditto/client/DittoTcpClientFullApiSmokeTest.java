package io.ditto.client;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.*;

class DittoTcpClientFullApiSmokeTest {

    @Test
    void tcpClientExercisesAuthCorePatternAndTtlCommands() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            FutureTask<Void> task = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    for (Step step : List.of(
                            new Step(DittoTcpClient.Wire.REQ_AUTH, response(DittoTcpClient.Wire.RESP_AUTH_OK, new byte[0])),
                            new Step(DittoTcpClient.Wire.REQ_SET, response(DittoTcpClient.Wire.RESP_OK, version(7))),
                            new Step(DittoTcpClient.Wire.REQ_GET, response(DittoTcpClient.Wire.RESP_VALUE, value("ns-key", "value", 8))),
                            new Step(DittoTcpClient.Wire.REQ_DELETE, response(DittoTcpClient.Wire.RESP_DELETED, new byte[0])),
                            new Step(DittoTcpClient.Wire.REQ_DELETE_BY_PATTERN, response(DittoTcpClient.Wire.RESP_PATTERN_DELETED, count(3))),
                            new Step(DittoTcpClient.Wire.REQ_SET_TTL_BY_PATTERN, response(DittoTcpClient.Wire.RESP_PATTERN_TTL_UPDATED, count(4)))
                    )) {
                        assertEquals(step.requestField, readVariant(in));
                        socket.getOutputStream().write(step.response);
                        socket.getOutputStream().flush();
                    }
                }
                return null;
            });
            new Thread(task).start();

            try (DittoTcpClient client = new DittoTcpClient(
                    "127.0.0.1",
                    server.getLocalPort(),
                    "token",
                    true,
                    false,
                    1000,
                    1000
            )) {
                client.connect();
                assertEquals(7, client.set("ns-key", "value", 30, "tenant-a").getVersion());
                DittoGetResult got = client.get("ns-key", "tenant-a");
                assertEquals("value", got.getValueAsString());
                assertEquals(8, got.getVersion());
                assertTrue(client.delete("ns-key", "tenant-a"));
                assertEquals(3, client.deleteByPattern("tenant:*", "tenant-a").getDeleted());
                assertEquals(4, client.setTtlByPattern("tenant:*", 45, "tenant-a").getUpdated());
            }
            task.get();
        }
    }

    @Test
    void tcpClientCoversErrorAndUnexpectedBranches() throws Exception {
        assertThrows(DittoException.class, () -> oneResponseClient(
                response(DittoTcpClient.Wire.RESP_ERROR, error(7, "slow"))
        ).get("k"));
        assertThrows(IOException.class, () -> oneResponseClient(
                response(DittoTcpClient.Wire.RESP_PONG, new byte[0])
        ).set("k", "v"));
        assertFalse(oneResponseClient(response(DittoTcpClient.Wire.RESP_NOT_FOUND, new byte[0])).delete("missing"));
        assertThrows(IOException.class, () -> oneResponseClient(
                response(DittoTcpClient.Wire.RESP_PONG, new byte[0])
        ).deleteByPattern("k*"));
    }

    private static DittoTcpClient oneResponseClient(byte[] response) throws IOException {
        ServerSocket server = new ServerSocket(0);
        new Thread(() -> {
            try (server; Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                readVariant(in);
                socket.getOutputStream().write(response);
                socket.getOutputStream().flush();
            } catch (IOException ignored) {
            }
        }).start();
        DittoTcpClient client = new DittoTcpClient("127.0.0.1", server.getLocalPort(), null, false, false, 1000, 1000);
        client.connect();
        return client;
    }

    private record Step(int requestField, byte[] response) {}

    private static int readVariant(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] payload = in.readNBytes(len);
        return ProtoTestSupport.readClientRequestVariant(payload);
    }

    private static byte[] response(int field, byte[] inner) {
        return ProtoTestSupport.frameClientResponse(field, inner);
    }

    private static byte[] version(long version) {
        return ProtoTestSupport.encodeVersionResponseInner(version);
    }

    private static byte[] count(long count) {
        DittoTcpClient.Wire.Writer w = new DittoTcpClient.Wire.Writer();
        w.uint64Field(DittoTcpClient.Wire.COUNT_FIELD, count);
        return w.toByteArray();
    }

    private static byte[] value(String key, String value, long version) {
        DittoTcpClient.Wire.Writer w = new DittoTcpClient.Wire.Writer();
        w.stringField(DittoTcpClient.Wire.VAL_KEY, key);
        w.bytesField(DittoTcpClient.Wire.VAL_VALUE, value.getBytes(StandardCharsets.UTF_8));
        w.uint64Field(DittoTcpClient.Wire.VAL_VERSION, version);
        return w.toByteArray();
    }

    private static byte[] error(int code, String message) {
        DittoTcpClient.Wire.Writer w = new DittoTcpClient.Wire.Writer();
        w.enumField(DittoTcpClient.Wire.ERR_CODE, code);
        w.stringField(DittoTcpClient.Wire.ERR_MESSAGE, message);
        return w.toByteArray();
    }
}
