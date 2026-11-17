package org.kendar.llm.eval.fixture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.docs.DocxReader;
import org.kendar.llm.pdf.PdfReader;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class DocumentsTest {

    @TempDir
    Path dir;

    @Test
    void pdfFactsAreReadBackByThePluginReader() throws Exception {
        Documents.Doc doc = Documents.pdf(dir, "report.pdf");
        String text = new PdfReader().extract(doc.file());
        assertEquals(3, doc.facts().size());
        for (Fact f : doc.facts()) {
            assertTrue(text.contains(f.answer()), "missing " + f.answer() + " in: " + text);
            assertEquals(1, count(text, f.answer()), f.answer() + " must occur once");
        }
        assertTrue(text.indexOf("2019") < text.indexOf("1850 EUR") && text.indexOf("1850 EUR") < text.indexOf("Marta Quist"));
    }

    @Test
    void docxFactsAreReadBackByThePluginReader() throws Exception {
        Documents.Doc doc = Documents.docx(dir, "handbook.docx");
        String text = new DocxReader().extract(doc.file());
        for (Fact f : doc.facts()) {
            assertTrue(text.contains(f.answer()), "missing " + f.answer() + " in: " + text);
        }
        assertTrue(text.contains("Falcon | 349"), "table row as the reader renders it: " + text);
        assertTrue(text.contains("# Supplier handbook"), text);
    }

    @Test
    void factAnswersAreNotInTheOtherDocument() throws Exception {
        String pdf = new PdfReader().extract(Documents.pdf(dir, "a.pdf").file());
        String docx = new DocxReader().extract(Documents.docx(dir, "b.docx").file());
        for (Fact f : Documents.docxFacts()) assertFalse(pdf.contains(f.answer()), f.answer());
        for (Fact f : Documents.pdfFacts()) assertFalse(docx.contains(f.answer()), f.answer());
    }

    @Test
    void corruptFilesFailInTheReaders() {
        Path pdf = Documents.corruptPdf(dir, "bad.pdf");
        Path docx = Documents.corruptDocx(dir, "bad.docx");
        assertThrows(IOException.class, () -> new PdfReader().extract(pdf));
        assertThrows(IOException.class, () -> new DocxReader().extract(docx));
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }
}
