package io.ditto.client;

/** Result returned by {@code waitForWatchEvent()}. */
public record DittoWatchEvent(String key, byte[] value, long version) {}
