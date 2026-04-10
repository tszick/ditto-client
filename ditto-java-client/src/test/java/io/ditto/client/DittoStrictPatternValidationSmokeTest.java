package io.ditto.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DittoStrictPatternValidationSmokeTest {

    @Test
    void httpStrictModeRejectsInvalidPatternBeforeNetwork() {
        DittoHttpClient http = new DittoHttpClient.Builder()
                .host("127.0.0.1")
                .port(1)
                .strictMode(true)
                .build();
        assertThrows(IllegalArgumentException.class, () -> http.deleteByPattern("bad pattern*"));
    }

    @Test
    void tcpStrictModeRejectsBlankNamespaceForPatternOpsBeforeNetwork() {
        DittoTcpClient tcp = new DittoTcpClient("127.0.0.1", 1, null, true, false);
        assertThrows(IllegalArgumentException.class, () -> tcp.deleteByPattern("ok:*", "   "));
    }
}
