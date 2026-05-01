package io.ditto.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DittoHttpClientFullApiSmokeTest {

    @Test
    void fullHttpSurfaceUsesHeadersPayloadsAndErrorMapping() throws Exception {
        List<String> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                assertEquals(
                        "Basic " + Base64.getEncoder().encodeToString("ditto:secret".getBytes(StandardCharsets.UTF_8)),
                        exchange.getRequestHeaders().getFirst("Authorization")
                );
                handle(exchange);
            } catch (Throwable t) {
                send(exchange, 500, "{\"message\":\"" + t.getMessage().replace("\"", "'") + "\"}");
            }
        });
        server.start();
        try {
            DittoHttpClient client = DittoHttpClient.builder()
                    .host("127.0.0.1")
                    .port(server.getAddress().getPort())
                    .username("ditto")
                    .password("secret")
                    .strictMode(true)
                    .connectTimeoutMs(1000)
                    .requestTimeoutMs(1000)
                    .build();
            try {
                assertTrue(client.ping());
                assertEquals(7, client.set("ns-key", "value", 30, "tenant-a").getVersion());
                DittoGetResult got = client.get("ns-key", "tenant-a");
                assertEquals("value", got.getValueAsString());
                assertEquals(7, got.getVersion());
                assertTrue(client.delete("ns-key"));
                assertFalse(client.delete("missing"));
                assertEquals(3, client.deleteByPattern("tenant:*", "tenant-a").getDeleted());
                assertEquals(4, client.setTtlByPattern("tenant:*", 45, null).getUpdated());
                DittoStatsResult stats = client.stats();
                assertEquals("n1", stats.getNodeId());
                assertEquals("active", stats.getStatus());
                assertTrue(stats.isPrimary());
                assertEquals(9, stats.getCommittedIndex());
                assertEquals(2, stats.getKeyCount());
                assertEquals(100, stats.getMemoryUsedBytes());
                assertEquals(1000, stats.getMemoryMaxBytes());
                assertEquals(1, stats.getEvictions());
                assertEquals(5, stats.getHitCount());
                assertEquals(6, stats.getMissCount());
                assertEquals(60, stats.getUptimeSecs());
                assertEquals(1024, stats.getValueSizeLimitBytes());
                assertEquals(100, stats.getMaxKeysLimit());
                assertTrue(stats.isCompressionEnabled());
                assertEquals(32, stats.getCompressionThresholdBytes());
                assertEquals("node-a", stats.getNodeName());
                assertEquals(77, stats.getBackupDirBytes());

                DittoException err = assertThrows(DittoException.class, () -> client.get("failing"));
                assertEquals(DittoErrorCode.RATE_LIMITED, err.getCode());
                assertEquals("slow down", err.getMessage());
            } finally {
                client.close();
            }
        } finally {
            server.stop(0);
        }
        assertTrue(seen.size() >= 9, "expected broad endpoint coverage, got " + seen);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().toString();
        if (method.equals("GET") && path.equals("/ping")) {
            send(exchange, 200, "{\"pong\":true}");
            return;
        }
        if (method.equals("PUT") && path.equals("/key/ns-key?ttl=30")) {
            assertEquals("tenant-a", exchange.getRequestHeaders().getFirst("X-Ditto-Namespace"));
            assertEquals("text/plain", exchange.getRequestHeaders().getFirst("Content-Type"));
            assertEquals("value", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"version\":7}");
            return;
        }
        if (method.equals("GET") && path.equals("/key/ns-key")) {
            assertEquals("tenant-a", exchange.getRequestHeaders().getFirst("X-Ditto-Namespace"));
            send(exchange, 200, "{\"value\":\"value\",\"version\":7}");
            return;
        }
        if (method.equals("DELETE") && path.equals("/key/ns-key")) {
            send(exchange, 204, "");
            return;
        }
        if (method.equals("DELETE") && path.equals("/key/missing")) {
            send(exchange, 404, "");
            return;
        }
        if (method.equals("POST") && path.equals("/keys/delete-by-pattern")) {
            assertEquals("tenant-a", exchange.getRequestHeaders().getFirst("X-Ditto-Namespace"));
            assertEquals("{\"pattern\":\"tenant:*\"}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"deleted\":3}");
            return;
        }
        if (method.equals("POST") && path.equals("/keys/ttl-by-pattern")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"pattern\":\"tenant:*\""), body);
            assertTrue(body.contains("\"ttl_secs\":45"), body);
            send(exchange, 200, "{\"updated\":4}");
            return;
        }
        if (method.equals("GET") && path.equals("/stats")) {
            send(exchange, 200, "{\"node_id\":\"n1\",\"status\":\"active\",\"is_primary\":true,"
                    + "\"committed_index\":9,\"key_count\":2,"
                    + "\"memory_used_bytes\":100,\"memory_max_bytes\":1000,"
                    + "\"evictions\":1,\"hit_count\":5,\"miss_count\":6,\"uptime_secs\":60,"
                    + "\"value_size_limit_bytes\":1024,\"max_keys_limit\":100,"
                    + "\"compression_enabled\":true,\"compression_threshold_bytes\":32,"
                    + "\"node_name\":\"node-a\",\"backup_dir_bytes\":77}");
            return;
        }
        if (method.equals("GET") && path.equals("/key/failing")) {
            send(exchange, 429, "{\"error\":\"RateLimited\",\"message\":\"slow down\"}");
            return;
        }
        send(exchange, 500, "{\"message\":\"unexpected " + method + " " + path + "\"}");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status != 204) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } else {
            exchange.sendResponseHeaders(status, -1);
        }
        exchange.close();
    }
}
