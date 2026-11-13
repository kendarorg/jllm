package org.kendar.llm.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.kendar.llm.harness.tools.DocumentReader;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Extracts the text of a PDF, page by page, with {@code --- page N ---} markers. No OCR.
 * <p>
 * The caller cancels a slow extraction by interrupting the thread: the interrupt flag is checked between pages and
 * between content-stream operators, so a single huge page is abandoned too.
 */
public final class PdfReader implements DocumentReader {

    /** Extraction stops after this many characters. */
    static final int MAX_CHARS = 5_000_000;
    /** Extraction stops after this many pages. */
    static final int MAX_PAGES = 2_000;
    /** Decoded streams are buffered in memory up to this size, then in a temp file. */
    static final long MAX_MAIN_MEMORY_BYTES = 64L * 1024 * 1024;
    /** Hard cap on the scratch storage (memory + temp file) used while parsing. */
    static final long MAX_STORAGE_BYTES = 1024L * 1024 * 1024;

    private final int maxPages;

    public PdfReader() {
        this(MAX_PAGES);
    }

    PdfReader(int maxPages) {
        this.maxPages = maxPages;
    }

    @Override
    public Set<String> extensions() {
        return Set.of("pdf");
    }

    @Override
    public String extract(Path file) throws IOException {
        checkInterrupted();
        // An owner password only restricts printing/copying: such a file opens with the empty user password.
        // A real user password makes the load fail with InvalidPasswordException.
        try (PDDocument doc = Loader.loadPDF(file.toFile(),
                MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES, MAX_STORAGE_BYTES).streamCache)) {
            if (doc.isEncrypted()) doc.setAllSecurityToBeRemoved(true);
            PDFTextStripper stripper = new InterruptibleTextStripper();
            StringBuilder sb = new StringBuilder();
            boolean anyText = false;
            int pages = doc.getNumberOfPages();
            int last = Math.min(pages, maxPages);
            for (int p = 1; p <= last && sb.length() < MAX_CHARS; p++) {
                checkInterrupted();
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc).strip();
                anyText |= !text.isEmpty();
                sb.append("--- page ").append(p).append(" ---\n").append(text).append('\n');
            }
            if (!anyText) return "no extractable text (scanned?)";
            if (pages > maxPages) {
                sb.append("[stopped after ").append(maxPages).append(" of ").append(pages).append(" pages]\n");
            }
            return sb.toString();
        } catch (InvalidPasswordException e) {
            throw new IOException("The PDF is password-protected");
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        // isInterrupted (not interrupted): the flag must survive for the caller and for nested checks.
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("PDF extraction interrupted");
    }

    /** Checks for cancellation before every content-stream operator. */
    private static final class InterruptibleTextStripper extends PDFTextStripper {
        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            checkInterrupted();
            super.processOperator(operator, operands);
        }

        /** The base class logs and swallows some failures (e.g. inside a form XObject); never a cancellation. */
        @Override
        protected void operatorException(Operator operator, List<COSBase> operands, IOException e) throws IOException {
            if (e instanceof InterruptedIOException) throw e;
            super.operatorException(operator, operands, e);
        }
    }
}
