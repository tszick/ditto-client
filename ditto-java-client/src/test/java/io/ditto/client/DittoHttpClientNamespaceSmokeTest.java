package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;

class DittoHttpClientNamespaceSmokeTest {

    @Test
    void requestBuilderAddsNamespaceHeaderWhenProvided() throws Exception {
        DittoHttpClient client = DittoHttpClient.builder().host("localhost").port(7778).build();

        Method requestBuilder = DittoHttpClientBase.class
                .getDeclaredMethod("requestBuilder", String.class, String.class);
        requestBuilder.setAccessible(true);

        HttpRequest.Builder builder = (HttpRequest.Builder) requestBuilder.invoke(client, "/ping", "tenant-a");
        HttpRequest req = builder.GET().build();

        assertEquals("tenant-a", req.headers().firstValue("X-Ditto-Namespace").orElse(null));
    }

    @Test
    void requestBuilderSkipsNamespaceHeaderWhenBlank() throws Exception {
        DittoHttpClient client = DittoHttpClient.builder().host("localhost").port(7778).build();

        Method requestBuilder = DittoHttpClientBase.class
                .getDeclaredMethod("requestBuilder", String.class, String.class);
        requestBuilder.setAccessible(true);

        HttpRequest blankReq = ((HttpRequest.Builder) requestBuilder.invoke(client, "/ping", "   ")).GET().build();
        HttpRequest nullReq = ((HttpRequest.Builder) requestBuilder.invoke(client, "/ping", null)).GET().build();

        assertFalse(blankReq.headers().firstValue("X-Ditto-Namespace").isPresent());
        assertTrue(nullReq.headers().firstValue("X-Ditto-Namespace").isEmpty());
    }
}
