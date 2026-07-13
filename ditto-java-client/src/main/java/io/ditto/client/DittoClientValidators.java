package io.ditto.client;

final class DittoClientValidators {

    private DittoClientValidators() {}

    static void validateCoreInputs(boolean strictMode, String op, String key, String namespace) {
        if (!strictMode) return;
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid " + op + " request: key must not be empty.");
        }
        if (!isStrictToken(key)) {
            throw new IllegalArgumentException(
                    "Invalid " + op + " request: key contains unsupported characters. Allowed: [A-Za-z0-9._:-]"
            );
        }
        validateNamespace(op, namespace);
    }

    static void validatePatternInputs(boolean strictMode, String op, String pattern, String namespace) {
        if (!strictMode) return;
        if (pattern == null || pattern.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid " + op + " request: pattern must not be empty.");
        }
        if (!isStrictPattern(pattern.trim())) {
            throw new IllegalArgumentException(
                    "Invalid " + op + " request: pattern contains unsupported characters. Allowed: [A-Za-z0-9._:-*]"
            );
        }
        validateNamespace(op, namespace);
    }

    static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void validateNamespace(String op, String namespace) {
        if (namespace == null) return;
        String ns = namespace.trim();
        if (ns.isEmpty()) {
            throw new IllegalArgumentException("Invalid " + op + " request: namespace must not be blank when provided.");
        }
        if (ns.contains("::")) {
            throw new IllegalArgumentException("Invalid " + op + " request: namespace must not contain '::'.");
        }
        if (!isStrictToken(ns)) {
            throw new IllegalArgumentException(
                    "Invalid " + op + " request: namespace contains unsupported characters. Allowed: [A-Za-z0-9._:-]"
            );
        }
    }

    private static boolean isStrictToken(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':') {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isStrictPattern(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':' || c == '*') {
                continue;
            }
            return false;
        }
        return true;
    }
}
