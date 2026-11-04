package org.kendar.llm.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.kendar.llm.harness.tools.DocumentReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Extracts the text of a PDF, page by page, with {@code --- page N ---} markers. No OCR. */
public final class PdfReader implements DocumentReader {

    /** Extraction stops after this many characters. */
    static final int MAX_CHARS = 5_000_000;

    @Override
    public Set<String> extensions() {
        return Set.of("pdf");
    }

    @Override
    public String extract(Path file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            if (doc.isEncrypted()) throw new IOException("The PDF is password-protected");
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder sb = new StringBuilder();
            boolean anyText = false;
            int pages = doc.getNumberOfPages();
            for (int p = 1; p <= pages && sb.length() < MAX_CHARS; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc).strip();
                anyText |= !text.isEmpty();
                sb.append("--- page ").append(p).append(" ---\n").append(text).append('\n');
            }
            if (!anyText) return "no extractable text (scanned?)";
            return sb.toString();
        } catch (InvalidPasswordException e) {
            throw new IOException("The PDF is password-protected");
        }
    }
}
