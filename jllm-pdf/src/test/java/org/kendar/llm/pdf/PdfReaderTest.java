package org.kendar.llm.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.plugin.HarnessPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class PdfReaderTest {

    @TempDir
    Path dir;

    private Path pdf(String... pageTexts) throws IOException {
        Path f = dir.resolve("t.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (String t : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (t.isEmpty()) continue;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(t);
                    cs.endText();
                }
            }
            doc.save(f.toFile());
        }
        return f;
    }

    @Test
    void extractsTextWithPageMarkers() throws Exception {
        String text = new PdfReader().extract(pdf("Hello first", "Second page"));

        assertTrue(text.contains("--- page 1 ---\nHello first"), text);
        assertTrue(text.contains("--- page 2 ---\nSecond page"), text);
    }

    @Test
    void textlessPdfIsReportedAsScanned() throws Exception {
        assertEquals("no extractable text (scanned?)", new PdfReader().extract(pdf("", "")));
    }

    @Test
    void corruptFileFails() throws Exception {
        Path f = dir.resolve("bad.pdf");
        Files.writeString(f, "not a pdf");
        assertThrows(IOException.class, () -> new PdfReader().extract(f));
    }

    @Test
    void pluginIsDiscoverable() {
        assertTrue(ServiceLoader.load(HarnessPlugin.class).stream().anyMatch(p -> p.type() == PdfPlugin.class));
    }
}
