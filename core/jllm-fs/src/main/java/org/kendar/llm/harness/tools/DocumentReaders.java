package org.kendar.llm.harness.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;

/** Registry of {@link DocumentReader}s by extension. */
public final class DocumentReaders {

    /** Extracted text beyond this is dropped. */
    public static final int MAX_TEXT_CHARS = 5_000_000;
    public static final int TIMEOUT_SECONDS = 30;
    /** The largest document handed to a reader; a stream that gives more is refused. */
    public static final long MAX_DOCUMENT_BYTES = 50_000_000;

    private final Map<String, DocumentReader> byExtension = new ConcurrentHashMap<>();

    public void register(DocumentReader reader) {
        for (String ext : reader.extensions()) byExtension.put(ext.toLowerCase(Locale.ROOT), reader);
    }

    /** @return the registered extensions (lower case, no dot), sorted */
    public List<String> extensions() {
        return byExtension.keySet().stream().sorted().toList();
    }

    /** @return the reader for the extension, or null */
    public DocumentReader get(String extension) {
        return byExtension.get(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * Runs the reader on {@code in} (opened through the path jail; closed here) with a time and size cap and splits the
     * result into lines.
     */
    static List<String> extractLines(DocumentReader reader, InputStream in, String fileName) throws IOException {
        InputStream capped = new CappedStream(in, MAX_DOCUMENT_BYTES);
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "document-reader");
            t.setDaemon(true);
            return t;
        });
        Future<String> f = ex.submit(() -> reader.extract(capped, fileName));
        try {
            String text = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS) + "\n[document text truncated at " + MAX_TEXT_CHARS + " chars]";
            }
            return List.of(text.split("\\R", -1));
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new IOException("Reading " + fileName + " took longer than " + TIMEOUT_SECONDS + "s");
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) throw io;
            throw new IOException(c.getClass().getSimpleName() + ": " + c.getMessage());
        } finally {
            ex.shutdownNow();
            try {
                in.close();
            } catch (IOException e) {
                // best effort: the text is already read
            }
        }
    }

    /** Fails once more than {@code limit} bytes were read. */
    private static final class CappedStream extends java.io.FilterInputStream {
        private long left;

        CappedStream(InputStream in, long limit) {
            super(in);
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --left < 0) throw tooLarge();
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && (left -= n) < 0) throw tooLarge();
            return n;
        }

        private static IOException tooLarge() {
            return new IOException("File too large (more than " + MAX_DOCUMENT_BYTES + " bytes)");
        }
    }
}
