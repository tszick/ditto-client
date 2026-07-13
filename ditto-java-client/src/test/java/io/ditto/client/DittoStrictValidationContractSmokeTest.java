package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DittoStrictValidationContractSmokeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void strictValidationContract() throws Exception {
        Path contractPath = Path.of("..", "contracts", "strict-validation.contract.json");
        JsonNode contract = MAPPER.readTree(Files.readString(contractPath, StandardCharsets.UTF_8));

        for (JsonNode c : contract.get("cases")) {
            JsonNode inputs = c.get("inputs");
            String kind = inputs.get("kind").asText();
            JsonNode expect = c.get("expect");

            switch (kind) {
                case "core" -> assertCoreCase(inputs, expect);
                case "pattern" -> assertPatternCase(inputs, expect);
                case "normalize_namespace" -> assertNormalizeNamespaceCase(inputs, expect);
                default -> throw new IllegalStateException("unsupported contract kind: " + kind);
            }
        }
    }

    private static void assertCoreCase(JsonNode c, JsonNode expect) {
        boolean strictMode = c.get("strict_mode").asBoolean();
        String operation = c.get("op").asText();
        String key = c.get("key").asText();
        String namespace = textOrNull(c.get("namespace"));

        if (expect.path("valid").asBoolean(false)) {
            assertDoesNotThrow(() -> DittoClientValidators.validateCoreInputs(strictMode, operation, key, namespace));
            return;
        }

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> DittoClientValidators.validateCoreInputs(strictMode, operation, key, namespace)
        );
        assertMessageContains(ex, expect);
    }

    private static void assertPatternCase(JsonNode c, JsonNode expect) {
        boolean strictMode = c.get("strict_mode").asBoolean();
        String operation = c.get("op").asText();
        String pattern = c.get("pattern").asText();
        String namespace = textOrNull(c.get("namespace"));

        if (expect.path("valid").asBoolean(false)) {
            assertDoesNotThrow(() -> DittoClientValidators.validatePatternInputs(strictMode, operation, pattern, namespace));
            return;
        }

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> DittoClientValidators.validatePatternInputs(strictMode, operation, pattern, namespace)
        );
        assertMessageContains(ex, expect);
    }

    private static void assertNormalizeNamespaceCase(JsonNode c, JsonNode expect) {
        boolean strictMode = c.get("strict_mode").asBoolean();
        String namespace = textOrNull(c.get("namespace"));

        if (strictMode && expect.hasNonNull("error_contains")) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> DittoClientValidators.validateCoreInputs(true, "get", "alpha:key", namespace)
            );
            assertMessageContains(ex, expect);
            return;
        }

        String normalized = DittoClientValidators.normalizeOptionalText(namespace);
        if (expect.get("normalized").isNull()) {
            assertNull(normalized);
            return;
        }
        assertEquals(expect.get("normalized").asText(), normalized);
    }

    private static void assertMessageContains(IllegalArgumentException ex, JsonNode expect) {
        String expectedText = expect.get("error_contains").asText();
        if (!ex.getMessage().contains(expectedText)) {
            throw new AssertionError("expected error containing \"" + expectedText + "\", got \"" + ex.getMessage() + "\"");
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
