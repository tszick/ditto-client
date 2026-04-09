package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DittoErrorCodeMappingSmokeTest {

    @Test
    void fromIndexMapsExtendedRuntimeCodes() {
        assertEquals(DittoErrorCode.RATE_LIMITED, DittoErrorCode.fromIndex(7));
        assertEquals(DittoErrorCode.CIRCUIT_OPEN, DittoErrorCode.fromIndex(8));
        assertEquals(DittoErrorCode.AUTH_FAILED, DittoErrorCode.fromIndex(9));
    }
}
