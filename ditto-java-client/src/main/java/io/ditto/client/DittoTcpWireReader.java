package io.ditto.client;

import java.io.IOException;

final class DittoTcpWireReader {
    private final byte[] buf;
    private int off;
    private final int end;

    DittoTcpWireReader(byte[] buf) { this(buf, 0, buf.length); }
    DittoTcpWireReader(byte[] buf, int off, int end) { this.buf = buf; this.off = off; this.end = end; }

    int remaining() { return end - off; }

    long readVarint() throws IOException {
        long result = 0;
        int shift = 0;
        while (off < end) {
            int b = buf[off++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 70) throw new IOException("varint too long");
        }
        throw new IOException("truncated varint");
    }

    int readVarintAsInt() throws IOException {
        long v = readVarint();
        if (v > Integer.MAX_VALUE || v < 0) throw new IOException("varint out of int range: " + v);
        return (int) v;
    }

    int[] readTag() throws IOException {
        long t = readVarint();
        return new int[] { (int) (t >>> 3), (int) (t & 0x7) };
    }

    byte[] readLD() throws IOException {
        int len = readVarintAsInt();
        if (off + len > end) throw new IOException("truncated length-delimited field");
        byte[] out = new byte[len];
        System.arraycopy(buf, off, out, 0, len);
        off += len;
        return out;
    }

    void skip(int wire) throws IOException {
        switch (wire) {
            case DittoTcpClient.Wire.WT_VARINT -> readVarint();
            case DittoTcpClient.Wire.WT_LD -> readLD();
            case 1 -> off += 8;
            case 5 -> off += 4;
            default -> throw new IOException("unsupported wire type: " + wire);
        }
    }
}
