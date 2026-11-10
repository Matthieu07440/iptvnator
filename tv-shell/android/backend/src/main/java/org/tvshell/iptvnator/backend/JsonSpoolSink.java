package org.tvshell.iptvnator.backend;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes a provider body (UTF-8) to {@code out} through a buffer, as it arrives, so that a JSON
 * answer can be passed to the WebView verbatim without ever being held in the heap:
 * <ul>
 *   <li>a leading UTF-8 BOM is dropped (axios strips it before parsing);</li>
 *   <li>when the body starts with '{' or '[' ({@link #isJson()}), a raw control character inside
 *       a string (a tab or line feed in a hand-typed plot) is written as a {@code \\u00XX}
 *       escape: the browser's JSON.parse rejects it, {@link Json#parse} accepts it.</li>
 * </ul>
 * Everything else is copied unchanged. Control characters and the quote and backslash that
 * delimit strings are single bytes in UTF-8, never part of a multi-byte sequence, so the state
 * machine works on bytes.
 */
final class JsonSpoolSink extends OutputStream {

    private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
    private static final byte[] HEX = "0123456789abcdef".getBytes();

    private final OutputStream out;
    private final byte[] buf = new byte[64 * 1024];
    private int count;
    private long written;
    /** BOM bytes matched so far; -1 once the start of the body is past. */
    private int bom;
    private boolean decided;
    private boolean json;
    private boolean inString;
    private boolean escape;

    JsonSpoolSink(OutputStream out) {
        this.out = out;
    }

    boolean isJson() {
        return json;
    }

    /** Bytes written to {@code out}, including what is still buffered. */
    long written() {
        return written;
    }

    @Override
    public void write(int b) throws IOException {
        b &= 0xff;
        if (!decided) {
            if (bom >= 0) {
                if (b == (BOM[bom] & 0xff)) {
                    if (++bom == BOM.length) bom = -1;
                    return;
                }
                for (int i = 0; i < bom; i++) put(BOM[i] & 0xff);
                bom = -1;
            }
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                put(b);
                return;
            }
            decided = true;
            json = b == '{' || b == '[';
        }
        if (json) {
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (b == '\\') {
                    escape = true;
                } else if (b == '"') {
                    inString = false;
                } else if (b < 0x20) {
                    put('\\');
                    put('u');
                    put('0');
                    put('0');
                    put(HEX[b >> 4]);
                    put(HEX[b & 0xf]);
                    return;
                }
            } else if (b == '"') {
                inString = true;
            }
        }
        put(b);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (decided && !json) {
            // Not JSON: copied as it is, without the per-byte state machine.
            flushBuffer();
            out.write(bytes, offset, length);
            written += length;
            return;
        }
        for (int i = 0; i < length; i++) write(bytes[offset + i]);
    }

    private void put(int b) throws IOException {
        if (count == buf.length) flushBuffer();
        buf[count++] = (byte) b;
        written++;
    }

    private void flushBuffer() throws IOException {
        if (count > 0) {
            out.write(buf, 0, count);
            count = 0;
        }
    }

    /** Writes out a partial BOM left at the very end, and the buffer. */
    void finish() throws IOException {
        if (!decided && bom > 0) {
            for (int i = 0; i < bom; i++) put(BOM[i] & 0xff);
            bom = -1;
        }
        flushBuffer();
        out.flush();
    }

    @Override
    public void flush() throws IOException {
        flushBuffer();
        out.flush();
    }

    @Override
    public void close() throws IOException {
        finish();
        out.close();
    }
}
