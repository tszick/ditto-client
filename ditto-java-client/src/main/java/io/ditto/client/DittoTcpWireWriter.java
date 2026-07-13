package io.ditto.client;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class DittoTcpWireWriter {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    void varint(long v) {
        if (v < 0) throw new IllegalArgumentException("negative varint");
        while ((v & ~0x7FL) != 0) {
            buf.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        buf.write((int) v);
    }

    void tag(int field, int wire) { varint(((long) field << 3) | wire); }

    void uint64Field(int field, long value) {
        if (value == 0) return;
        tag(field, DittoTcpClient.Wire.WT_VARINT);
        varint(value);
    }

    void int64Field(int field, long value) {
        tag(field, DittoTcpClient.Wire.WT_VARINT);
        long v = value;
        while ((v & ~0x7FL) != 0) {
            buf.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        buf.write((int) v);
    }

    void enumField(int field, int value) {
        if (value == 0) return;
        tag(field, DittoTcpClient.Wire.WT_VARINT);
        varint(value);
    }

    void ldField(int field, byte[] payload) {
        if (payload.length == 0) return;
        tag(field, DittoTcpClient.Wire.WT_LD);
        varint(payload.length);
        buf.writeBytes(payload);
    }

    void ldFieldAlways(int field, byte[] payload) {
        tag(field, DittoTcpClient.Wire.WT_LD);
        varint(payload.length);
        if (payload.length > 0) buf.writeBytes(payload);
    }

    void stringField(int field, String value) {
        if (value == null || value.isEmpty()) return;
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        tag(field, DittoTcpClient.Wire.WT_LD);
        varint(raw.length);
        buf.writeBytes(raw);
    }

    void bytesField(int field, byte[] value) {
        if (value == null || value.length == 0) return;
        tag(field, DittoTcpClient.Wire.WT_LD);
        varint(value.length);
        buf.writeBytes(value);
    }

    byte[] toByteArray() { return buf.toByteArray(); }
}
