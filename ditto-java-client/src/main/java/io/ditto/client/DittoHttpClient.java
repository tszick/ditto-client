// Generated from api/ditto-http-api.yaml v1.1.0
// DO NOT EDIT MANUALLY — regenerate with: cd src/tools && npm run generate

package io.ditto.client;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class DittoHttpClient extends DittoHttpClientBase {

    // ── Factory / Builder ─────────────────────────────────────────────────────

    public static Builder builder()              { return new Builder(); }
    public static DittoHttpClient connect()      { return builder().build(); }
    public static DittoHttpClient connect(String host) { return builder().host(host).build(); }
    public static DittoHttpClient connect(String host, int port) {
        return builder().host(host).port(port).build();
    }

    public DittoHttpClient(DittoHttpClientBase.Builder<?> b) { super(b); }

    public static final class Builder extends DittoHttpClientBase.Builder<Builder> {
        @Override public DittoHttpClient build() { return new DittoHttpClient(this); }
    }

    // ── Generated endpoint methods (from api/ditto-http-api.yaml) ──────────

    /** Check whether the node is alive and accepting requests. */
    public boolean ping() throws IOException, InterruptedException {
        HttpResponse<String> resp = send(requestBuilder("/ping").GET().build());
        if (resp.statusCode() != 200) return false;
        Map<?, ?> body = mapper.readValue(resp.body(), Map.class);
        return Boolean.TRUE.equals(body.get("pong"));
    }

    /** Get a value by key. Returns null when the key does not exist or has expired. */
    public DittoGetResult get(String key) throws IOException, InterruptedException {
        return get(key, null);
    }

    public DittoGetResult get(String key, String namespace) throws IOException, InterruptedException {
        validateCoreInputs("get", key, namespace);
        HttpResponse<String> resp = send(requestBuilder("/key/" + urlEncode(key), namespace).GET().build());
        if (resp.statusCode() == 404) return null;
        assertOk(resp);
        Map<?, ?> body    = mapper.readValue(resp.body(), Map.class);
        String    value   = (String) body.get("value");
        long      version = ((Number) body.get("version")).longValue();
        return new DittoGetResult(value.getBytes(StandardCharsets.UTF_8), version);
    }

    /** Set a value. ttlSecs = 0 or omitted means no expiry. */
    public DittoSetResult set(String key, String value) throws IOException, InterruptedException {
        return set(key, value, 0, null);
    }

    /** Set a value. ttlSecs = 0 or omitted means no expiry. */
    public DittoSetResult set(String key, String value, long ttlSecs) throws IOException, InterruptedException {
        return set(key, value, ttlSecs, null);
    }

    /** Set a value in a specific namespace. ttlSecs = 0 or omitted means no expiry. */
    public DittoSetResult set(String key, String value, long ttlSecs, String namespace) throws IOException, InterruptedException {
        validateCoreInputs("set", key, namespace);
        String path = "/key/" + urlEncode(key) + (ttlSecs > 0 ? "?ttl=" + ttlSecs : "");
        HttpRequest req = requestBuilder(path, namespace)
                .PUT(HttpRequest.BodyPublishers.ofString(value))
                .header("Content-Type", "text/plain")
                .build();
        HttpResponse<String> resp = send(req);
        assertOk(resp);
        Map<?, ?> body    = mapper.readValue(resp.body(), Map.class);
        long      version = ((Number) body.get("version")).longValue();
        return new DittoSetResult(version);
    }

    /** Delete a key. Returns true if the key existed, false if not found. */
    public boolean delete(String key) throws IOException, InterruptedException {
        return delete(key, null);
    }

    public boolean delete(String key, String namespace) throws IOException, InterruptedException {
        validateCoreInputs("delete", key, namespace);
        HttpResponse<String> resp = send(requestBuilder("/key/" + urlEncode(key), namespace).DELETE().build());
        if (resp.statusCode() == 404) return false;
        if (resp.statusCode() == 204) return true;
        assertOk(resp);
        return true;
    }

    /** Delete all keys matching a glob-style pattern ('*' wildcard). */
    public DittoDeleteByPatternResult deleteByPattern(String pattern) throws IOException, InterruptedException {
        return deleteByPattern(pattern, null);
    }

    public DittoDeleteByPatternResult deleteByPattern(String pattern, String namespace) throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(Map.of("pattern", pattern));
        HttpRequest req = requestBuilder("/keys/delete-by-pattern", namespace)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> resp = send(req);
        assertOk(resp);
        Map<?, ?> payload = mapper.readValue(resp.body(), Map.class);
        long deleted = ((Number) payload.get("deleted")).longValue();
        return new DittoDeleteByPatternResult(deleted);
    }

    /**
     * Update TTL for all keys matching a glob-style pattern ('*' wildcard).
     * ttlSecs <= 0 removes TTL from matched keys.
     */
    public DittoSetTtlByPatternResult setTtlByPattern(String pattern, long ttlSecs)
            throws IOException, InterruptedException {
        return setTtlByPattern(pattern, ttlSecs, null);
    }

    public DittoSetTtlByPatternResult setTtlByPattern(String pattern, long ttlSecs, String namespace)
            throws IOException, InterruptedException {
        String body = ttlSecs > 0
                ? mapper.writeValueAsString(Map.of("pattern", pattern, "ttl_secs", ttlSecs))
                : mapper.writeValueAsString(Map.of("pattern", pattern));
        HttpRequest req = requestBuilder("/keys/ttl-by-pattern", namespace)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> resp = send(req);
        assertOk(resp);
        Map<?, ?> payload = mapper.readValue(resp.body(), Map.class);
        long updated = ((Number) payload.get("updated")).longValue();
        return new DittoSetTtlByPatternResult(updated);
    }

    /** Return cache statistics for this node. Available on HTTP client only. */
    public DittoStatsResult stats() throws IOException, InterruptedException {
        HttpResponse<String> resp = send(requestBuilder("/stats").GET().build());
        assertOk(resp);
        return mapper.readValue(resp.body(), DittoStatsResult.class);
    }

}
