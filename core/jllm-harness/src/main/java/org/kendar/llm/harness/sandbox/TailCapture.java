package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads a process output keeping only the last {@code limit} bytes in memory. Once the output exceeds the limit the
 * output is also streamed to a temp file, which is itself capped at {@code logLimit} bytes (then marked truncated).
 */
final class TailCapture {

    /** Default cap of the spill log. */
    static final long DEFAULT_LOG_LIMIT = 50 * 1024;

    /** Reader threads of all captures: daemon threads, never the common pool (a reader can block for long). */
    private static final ExecutorService READERS = Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "sandbox-output-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private final int limit;
    private final long logLimit;
    private final byte[] ring;
    private long total;
    private long newlines;
    private long logged;
    private boolean logTruncated;
    private boolean detached;
    private Path log;
    private OutputStream logOut;

    TailCapture(int limit) {
        this(limit, DEFAULT_LOG_LIMIT);
    }

    /** @param logLimit bytes written to the spill log at most; {@code <= 0}: unlimited */
    TailCapture(int limit, long logLimit) {
        this.limit = limit;
        this.logLimit = logLimit <= 0 ? Long.MAX_VALUE : logLimit;
        this.ring = new byte[limit];
    }

    /** Starts {@link #read} on the reader pool. */
    CompletableFuture<TailCapture> start(InputStream in) {
        return CompletableFuture.supplyAsync(() -> read(in), READERS);
    }

    /**
     * Waits up to {@code wait} for {@code reader} to reach the end of {@code in}. A process that exited but left a
     * background child holding the pipe never gets there: the output read so far is kept, later output is discarded
     * and the stream is closed in the background. Returns true when the stream ended in time.
     */
    boolean await(CompletableFuture<TailCapture> reader, InputStream in, Duration wait) throws InterruptedException {
        try {
            reader.get(Math.max(0, wait.toMillis()), TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException e) {
            return true;
        } catch (TimeoutException e) {
            detach();
            READERS.execute(() -> {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // already closed
                }
            });
            return false;
        }
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
            closeLog();
        }
        return this;
    }

    /** Stops keeping output: what was read so far is final, the log is closed. */
    synchronized void detach() {
        detached = true;
        closeLog();
    }

    private synchronized void closeLog() {
        if (logOut != null) {
            try {
                logOut.close();
            } catch (IOException ignored) {
            }
            logOut = null;
        }
    }

    private synchronized void accept(byte[] buf, int n) throws IOException {
        if (detached) return; // keep draining so a background writer does not block, but discard
        for (int i = 0; i < n; i++) if (buf[i] == '\n') newlines++;
        if (total + n > limit && log == null) {
            log = Files.createTempFile("bash-", ".log");
            logOut = Files.newOutputStream(log);
            byte[] head = tailBytes(total);
            writeLog(head, head.length);
        }
        if (logOut != null) writeLog(buf, n);
        for (int i = 0; i < n; i++) ring[(int) ((total + i) % limit)] = buf[i];
        total += n;
    }

    private void writeLog(byte[] buf, int n) throws IOException {
        if (logTruncated || logOut == null) return;
        long room = logLimit - logged;
        if (n <= room) {
            logOut.write(buf, 0, n);
            logged += n;
            return;
        }
        logOut.write(buf, 0, (int) room);
        logged += room;
        logOut.write(("\n[log truncated at " + logLimit + " bytes]\n").getBytes(StandardCharsets.UTF_8));
        logTruncated = true;
        closeLog();
    }

    private byte[] tailBytes(long upTo) {
        int len = (int) Math.min(upTo, limit);
        byte[] out = new byte[len];
        long start = upTo - len;
        for (int i = 0; i < len; i++) out[i] = ring[(int) ((start + i) % limit)];
        return out;
    }

    synchronized boolean truncated() {
        return total > limit;
    }

    /** True when the spill log hit its cap. */
    synchronized boolean logTruncated() {
        return logTruncated;
    }

    synchronized Path log() {
        return log;
    }

    /** The kept text; when truncated it starts at a line boundary. */
    synchronized String text() {
        String s = new String(tailBytes(total), StandardCharsets.UTF_8);
        if (truncated()) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
        }
        return s;
    }

    synchronized long omittedLines() {
        if (!truncated()) return 0;
        long kept = text().chars().filter(c -> c == '\n').count();
        return Math.max(0, newlines - kept);
    }
}
