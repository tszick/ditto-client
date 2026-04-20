package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DittoErrorCodePolicySmokeTest {

    @Test
    void unknownServerErrorCodeIsPreservedAsRawCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/key/x", ex -> {
            byte[] body = "{\"error\":\"NamespaceQuotaExceeded\",\"message\":\"quota hit\"}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(409, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        DittoHttpClient client = DittoHttpClient.builder().host("127.0.0.1").port(port).build();
        try {
            DittoException ex = assertThrows(DittoException.class, () -> client.get("x"));
            assertEquals(DittoErrorCode.NAMESPACE_QUOTA_EXCEEDED, ex.getCode());
            assertEquals("NamespaceQuotaExceeded", ex.getRawCode());
            assertEquals("quota hit", ex.getMessage());
        } finally {
            client.close();
            server.stop(0);
        }
    }
}
