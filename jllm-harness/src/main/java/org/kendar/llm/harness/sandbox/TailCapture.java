package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a process output keeping only the last {@code limit} bytes in memory. Once the output exceeds the limit
 * the complete output is streamed to a temp file.
 */
final class TailCapture {

    private final int limit;
    private final byte[] ring;
    private long total;
    private long newlines;
    private Path log;
    private OutputStream logOut;

    TailCapture(int limit) {
        this.limit = limit;
        this.ring = new byte[limit];
    }

    /** Blocks until the stream ends. */
    TailCapture read(InputStream in) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) accept(buf, n);
        } catch (IOException ignored) {
            // stream closed: keep what was read
        } finally {
            if (logOut != null) {
                try {
                    logOut.close();
                } catch (IOException ignored) {
                }
            }
        }
        return this;
    }

    private void accept(byte[] buf, int n) throws IOException {
        for (int i = 0; i < n; i++) if (buf[i] == '\n') newlines++;
        if (total + n > limit && logOut == null) {
            log = Files.createTempFile("bash-", ".log");
            logOut = Files.newOutputStream(log);
            logOut.write(tailBytes(total));
        }
        if (logOut != null) logOut.write(buf, 0, n);
        for (int i = 0; i < n; i++) ring[(int) ((total + i) % limit)] = buf[i];
        total += n;
    }

    private byte[] tailBytes(long upTo) {
        int len = (int) Math.min(upTo, limit);
        byte[] out = new byte[len];
        long start = upTo - len;
        for (int i = 0; i < len; i++) out[i] = ring[(int) ((start + i) % limit)];
        return out;
    }

    boolean truncated() {
        return total > limit;
    }

    Path log() {
        return log;
    }

    /** The kept text; when truncated it starts at a line boundary. */
    String text() {
        String s = new String(tailBytes(total), StandardCharsets.UTF_8);
        if (truncated()) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
        }
        return s;
    }

    long omittedLines() {
        if (!truncated()) return 0;
        long kept = text().chars().filter(c -> c == '\n').count();
        return Math.max(0, newlines - kept);
    }
}
