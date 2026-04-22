package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DittoHttpClientTlsPolicySmokeTest {

    @Test
    void rejectUnauthorizedFalseWithoutDevFlagIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                DittoHttpClient.builder()
                        .host("localhost")
                        .port(7778)
                        .tls(true)
                        .rejectUnauthorized(false)
                        .build()
        );
    }

    @Test
    void devInsecureTlsIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                DittoHttpClient.builder()
                        .host("localhost")
                        .port(7778)
                        .tls(true)
                        .devInsecureTls(true)
                        .build()
        );
    }
}
