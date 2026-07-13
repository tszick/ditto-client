package io.ditto.client;

import com.fasterxml.jackson.databind.ObjectMapper;

final class DittoAtomicErrorNormalizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DittoAtomicErrorNormalizer() {}

    static void throwIfHttpAtomicUnsupported(String responseBody, int statusCode, String operation) {
        if (statusCode != 400 && statusCode != 404 && statusCode != 501) return;
        try {
            var body = MAPPER.readValue(responseBody, java.util.Map.class);
            if ("UnsupportedRequest".equals(body.get("error"))) {
                Object msg = body.get("message");
                throw new DittoException(
                        DittoErrorCode.UNSUPPORTED_REQUEST,
                        msg != null ? msg.toString() : "UnsupportedRequest",
                        "UnsupportedRequest");
            }
        } catch (DittoException e) {
            throw e;
        } catch (Exception ignored) {
            // non-JSON body: fall through to normalized message
        }
        throw unsupportedAtomicOperation(operation);
    }

    static RuntimeException normalizeTcpAtomicError(Exception error, String operation) {
        if (error instanceof DittoException ditto) {
            return ditto;
        }
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        if (message.contains("unsupported")
                || message.contains("protocol")
                || message.contains("decode")
                || message.contains("clientresponse oneof")
                || message.contains("unexpected response")
                || message.contains("eof")
                || message.contains("connection reset")) {
            return unsupportedAtomicOperation(operation);
        }
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        return new RuntimeException(error);
    }

    private static DittoException unsupportedAtomicOperation(String operation) {
        return new DittoException(
                DittoErrorCode.UNSUPPORTED_REQUEST,
                "UnsupportedRequest: server does not support " + operation
                        + ". Upgrade dittod to a version with atomic primitives.",
                "UnsupportedRequest");
    }
}
