package org.kendar.llm.harness.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Registry of {@link DocumentReader}s by extension. */
public final class DocumentReaders {

    /** Extracted text beyond this is dropped. */
    public static final int MAX_TEXT_CHARS = 5_000_000;
    public static final int TIMEOUT_SECONDS = 30;

    private final Map<String, DocumentReader> byExtension = new ConcurrentHashMap<>();

    public void register(DocumentReader reader) {
        for (String ext : reader.extensions()) byExtension.put(ext.toLowerCase(Locale.ROOT), reader);
    }

    /** @return the reader for the extension, or null */
    public DocumentReader get(String extension) {
        return byExtension.get(extension.toLowerCase(Locale.ROOT));
    }

    /** Runs the reader with a time and size cap and splits the result into lines. */
    static List<String> extractLines(DocumentReader reader, Path file) throws IOException {
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "document-reader");
            t.setDaemon(true);
            return t;
        });
        Future<String> f = ex.submit(() -> reader.extract(file));
        try {
            String text = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS) + "\n[document text truncated at " + MAX_TEXT_CHARS + " chars]";
            }
            return List.of(text.split("\\R", -1));
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new IOException("Reading " + file.getFileName() + " took longer than " + TIMEOUT_SECONDS + "s");
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
        }
    }
}
