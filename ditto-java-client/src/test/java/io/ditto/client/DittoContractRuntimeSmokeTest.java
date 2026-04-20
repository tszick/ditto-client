package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DittoContractRuntimeSmokeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Entry> STORE = new HashMap<>();
    private static long version = 0;
    private static HttpServer server;
    private static DittoHttpClient client;
    private static JsonNode contract;

    @BeforeAll
    static void setup() throws Exception {
        STORE.clear();
        version = 0;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", DittoContractRuntimeSmokeTest::handle);
        server.start();
        int port = server.getAddress().getPort();
        client = DittoHttpClient.builder().host("127.0.0.1").port(port).build();

        Path contractPath = Path.of("..", "contracts", "core-ops.contract.json");
        contract = MAPPER.readTree(Files.readString(contractPath, StandardCharsets.UTF_8));
    }

    @AfterAll
    static void teardown() {
        client.close();
        server.stop(0);
    }

    @Test
    void coreOpsContractRuntime() throws Exception {
        for (JsonNode c : contract.get("cases")) {
            String operation = c.get("operation").asText();
            switch (operation) {
                case "ping" -> assertEquals(c.get("expect").get("value").asBoolean(), client.ping());
                case "set_get" -> {
                    String key = c.get("inputs").get("key").asText();
                    String value = c.get("inputs").get("value").asText();
                    long ttl = c.get("inputs").get("ttl_secs").asLong();
                    client.set(key, value, ttl);
                    DittoGetResult got = client.get(key);
                    assertNotNull(got);
                    assertEquals(c.get("expect").get("value_equals").asText(), got.getValueAsString());
                }
                case "delete" -> {
                    String key = c.get("inputs").get("key").asText();
                    boolean deleted = client.delete(key);
                    assertEquals(c.get("expect").get("value").asBoolean(), deleted);
                }
                case "delete_by_pattern" -> {
                    client.set("contract:prefix:a", "a");
                    client.set("contract:prefix:b", "b");
                    DittoDeleteByPatternResult out = client.deleteByPattern(c.get("inputs").get("pattern").asText());
                    assertTrue(out.getDeleted() >= c.get("expect").get("min").asLong());
                }
                default -> throw new IllegalStateException("unsupported operation in contract: " + operation);
            }
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if ("GET".equals(method) && "/ping".equals(path)) {
            sendJson(ex, 200, "{\"pong\":true}");
            return;
        }

        if (path.startsWith("/key/")) {
            String key = URLDecoder.decode(path.substring("/key/".length()), StandardCharsets.UTF_8);
            if ("PUT".equals(method)) {
                String value = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                version += 1;
                STORE.put(key, new Entry(value, version));
                sendJson(ex, 200, "{\"version\":" + version + "}");
                return;
            }
            if ("GET".equals(method)) {
                Entry entry = STORE.get(key);
                if (entry == null) {
                    sendStatus(ex, 404);
                    return;
                }
                sendJson(ex, 200, "{\"value\":\"" + escapeJson(entry.value) + "\",\"version\":" + entry.version + "}");
                return;
            }
            if ("DELETE".equals(method)) {
                if (STORE.remove(key) == null) {
                    sendStatus(ex, 404);
                    return;
                }
                sendStatus(ex, 204);
                return;
            }
        }

        if ("POST".equals(method) && "/keys/delete-by-pattern".equals(path)) {
            JsonNode payload = MAPPER.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String prefix = payload.get("pattern").asText().replaceAll("\\*+$", "");
            long deleted = 0;
            Iterator<String> it = STORE.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                if (key.startsWith(prefix)) {
                    it.remove();
                    deleted += 1;
                }
            }
            sendJson(ex, 200, "{\"deleted\":" + deleted + "}");
            return;
        }

        sendStatus(ex, 404);
    }

    private static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    private static void sendStatus(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record Entry(String value, long version) {}
}
