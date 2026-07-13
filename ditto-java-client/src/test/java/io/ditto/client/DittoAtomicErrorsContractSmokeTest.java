package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DittoAtomicErrorsContractSmokeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void atomicErrorsContract() throws Exception {
        Path contractPath = Path.of("..", "contracts", "atomic-errors.contract.json");
        JsonNode contract = MAPPER.readTree(Files.readString(contractPath, StandardCharsets.UTF_8));

        for (JsonNode c : contract.get("cases")) {
            String operation = c.get("operation").asText();
            JsonNode inputs = c.get("inputs");
            JsonNode expect = c.get("expect");

            RuntimeException error = switch (operation) {
                case "normalize_http_atomic_error" -> captureHttpAtomicNormalization(inputs);
                case "normalize_tcp_atomic_error" -> captureTcpAtomicNormalization(inputs);
                default -> throw new IllegalStateException("unsupported contract operation: " + operation);
            };

            if (!(error instanceof DittoException ditto)) {
                throw new AssertionError("expected DittoException, got " + error.getClass().getName());
            }
            assertEquals(expect.get("code").asText(), ditto.getRawCode());
            assertTrue(ditto.getMessage().contains(expect.get("message_contains").asText()));
        }
    }

    private static RuntimeException captureHttpAtomicNormalization(JsonNode inputs) {
        try {
            DittoAtomicErrorNormalizer.throwIfHttpAtomicUnsupported(
                    inputs.get("body").asText(),
                    inputs.get("status").asInt(),
                    inputs.get("operation_name").asText()
            );
            throw new AssertionError("expected HTTP atomic normalization to throw");
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static RuntimeException captureTcpAtomicNormalization(JsonNode inputs) {
        Exception source;
        if ("ditto".equals(inputs.get("error_kind").asText())) {
            source = new DittoException(
                    DittoErrorCode.valueOf(inputs.get("error_code").asText().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase()),
                    inputs.get("error_message").asText(),
                    inputs.get("error_code").asText()
            );
        } else {
            source = new Exception(inputs.get("error_message").asText());
        }
        return DittoAtomicErrorNormalizer.normalizeTcpAtomicError(source, inputs.get("operation_name").asText());
    }
}
