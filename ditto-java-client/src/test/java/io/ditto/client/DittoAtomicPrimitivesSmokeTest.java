package io.ditto.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.*;

/** Coverage for the atomic SET_NX / INCR primitives over both transports. */
class DittoAtomicPrimitivesSmokeTest {

    // ── Wire-level (TCP) ──────────────────────────────────────────────────────

    @Test
    void tcpSetNxAndIncrDecodeIncludingNegativeAndExistingKey() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            FutureTask<Void> task = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    for (Step step : List.of(
                            new Step(DittoTcpClient.Wire.REQ_SET_NX, frame(DittoTcpClient.Wire.RESP_SET_NX, ProtoTestSupport.encodeSetNxInner(true, 1))),
                            new Step(DittoTcpClient.Wire.REQ_SET_NX, frame(DittoTcpClient.Wire.RESP_SET_NX, ProtoTestSupport.encodeSetNxInner(false, 1))),
                            new Step(DittoTcpClient.Wire.REQ_INCR, frame(DittoTcpClient.Wire.RESP_COUNTER, ProtoTestSupport.encodeCounterInner(5, 2))),
                            new Step(DittoTcpClient.Wire.REQ_INCR, frame(DittoTcpClient.Wire.RESP_COUNTER, ProtoTestSupport.encodeCounterInner(-3, 3)))
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
                    "127.0.0.1", server.getLocalPort(), null, false, false, 1000, 1000)) {
                client.connect();

                DittoSetNxResult created = client.setNx("k", "v", 60);
                assertTrue(created.isCreated());
                assertEquals(1, created.getVersion());

                DittoSetNxResult existing = client.setNx("k", "v2");
                assertFalse(existing.isCreated());
                assertEquals(1, existing.getVersion());

                DittoCounterResult up = client.incr("c", 5, 30, null);
                assertEquals(5, up.getValue());
                assertEquals(2, up.getVersion());

                DittoCounterResult down = client.incr("c", -8);
                assertEquals(-3, down.getValue());
                assertEquals(3, down.getVersion());
            }
            task.get();
        }
    }

    @Test
    void tcpIncrTypeMismatchSurfacesAsDittoException() throws Exception {
        DittoTcpClient client = oneResponseClient(
                frame(DittoTcpClient.Wire.RESP_ERROR, ProtoTestSupport.encodeErrorInner(12, "not a counter")));
        DittoException ex = assertThrows(DittoException.class, () -> client.incr("c"));
        assertEquals(DittoErrorCode.TYPE_MISMATCH, ex.getCode());
    }

    @Test
    void errorCodeMappingCoversAtomicCodes() {
        assertEquals(DittoErrorCode.UNSUPPORTED_REQUEST, DittoErrorCode.fromIndex(11));
        assertEquals(DittoErrorCode.TYPE_MISMATCH, DittoErrorCode.fromIndex(12));
        assertEquals(DittoErrorCode.OVERFLOW, DittoErrorCode.fromIndex(13));
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    @Test
    void httpSetNxAndIncrParseStringInt64AndUnsupported() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String target = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            byte[] body = exchange.getRequestBody().readAllBytes();  // drain
            assertNotNull(body);
            switch (target) {
                case "POST /key/new?nx=1&ttl=60" -> send(exchange, 201, "{\"created\":true,\"version\":\"1\"}");
                // SET_NX on an existing key MUST be 200, not 409.
                case "POST /key/dupe?nx=1" -> send(exchange, 200, "{\"created\":false,\"version\":\"9\"}");
                case "POST /key/counter/incr" -> send(exchange, 200, "{\"value\":\"5\",\"version\":\"2\"}");
                case "POST /key/legacy/incr" -> send(exchange, 501, "{\"error\":\"UnsupportedRequest\",\"message\":\"no\"}");
                case "POST /key/badtype/incr" -> send(exchange, 409, "{\"error\":\"TypeMismatch\",\"message\":\"nope\"}");
                default -> send(exchange, 500, "{\"message\":\"unexpected " + target + "\"}");
            }
        });
        server.start();
        DittoHttpClient client = DittoHttpClient.builder()
                .host("127.0.0.1").port(server.getAddress().getPort())
                .connectTimeoutMs(1000).requestTimeoutMs(1000).build();
        try {
            DittoSetNxResult created = client.setNx("new", "v", 60);
            assertTrue(created.isCreated());
            assertEquals(1, created.getVersion());

            DittoSetNxResult existing = client.setNx("dupe", "v");
            assertFalse(existing.isCreated());
            assertEquals(9, existing.getVersion());

            DittoCounterResult counter = client.incr("counter", 5);
            assertEquals(5, counter.getValue());
            assertEquals(2, counter.getVersion());

            DittoException unsupported = assertThrows(DittoException.class, () -> client.incr("legacy"));
            assertEquals(DittoErrorCode.UNSUPPORTED_REQUEST, unsupported.getCode());

            DittoException mismatch = assertThrows(DittoException.class, () -> client.incr("badtype"));
            assertEquals(DittoErrorCode.TYPE_MISMATCH, mismatch.getCode());
        } finally {
            client.close();
            server.stop(0);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private record Step(int requestField, byte[] response) {}

    private static byte[] frame(int field, byte[] inner) {
        return ProtoTestSupport.frameClientResponse(field, inner);
    }

    private static int readVariant(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] payload = in.readNBytes(len);
        return ProtoTestSupport.readClientRequestVariant(payload);
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

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
