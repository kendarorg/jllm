package org.kendar.llm.eval.fixture;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PDF and Word files generated at runtime, each with a list of facts really present in its text. Pages and
 * paragraphs are written so every fact sits on one line and survives text extraction.
 */
public final class Documents {

    /** A generated file and the facts it contains. */
    public record Doc(Path file, List<Fact> facts) {
    }

    private static final List<Fact> PDF_FACTS = List.of(
            new Fact("In which year did the Helsinki warehouse open?", "2019"),
            new Fact("What is the approval limit for travel expenses?", "1850 EUR"),
            new Fact("Who owns the Atlas migration project?", "Marta Quist"));

    private static final List<Fact> DOCX_FACTS = List.of(
            new Fact("What is the notice period in the supplier contract?", "45 days"),
            new Fact("Which city hosts the annual partner summit?", "Gdansk"),
            new Fact("What does the Falcon product cost?", "349"));

    private Documents() {
    }

    /** Facts of {@link #pdf(Path, String)}: the first on page 1, the second on page 2, the third on page 3. */
    public static List<Fact> pdfFacts() {
        return PDF_FACTS;
    }

    /** Facts of {@link #docx(Path, String)}: two in paragraphs, the last in the cell of a table. */
    public static List<Fact> docxFacts() {
        return DOCX_FACTS;
    }

    /** Writes a three-page PDF, one fact per page, to {@code dir/name}. */
    public static Doc pdf(Path dir, String name) {
        String[][] pages = {
                {"Quarterly operations report", "",
                        "This report summarizes logistics and projects for the quarter.",
                        "The Helsinki warehouse opened in 2019 and serves the northern region.",
                        "Capacity planning is reviewed twice a year."},
                {"Finance and policy", "",
                        "Expense rules were simplified this quarter.",
                        "The approval limit for travel expenses is 1850 EUR per trip.",
                        "Receipts must be filed within thirty days."},
                {"Projects", "",
                        "Three projects are in progress.",
                        "The Atlas migration project is owned by Marta Quist.",
                        "Status reports are due on the first Monday of each month."}};
        Path file = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String[] lines : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.setLeading(18);
                    cs.newLineAtOffset(60, 760);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            Files.createDirectories(dir);
            doc.save(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Doc(file, PDF_FACTS);
    }

    /** Writes a minimal docx with a heading, two fact paragraphs and a price table, to {@code dir/name}. */
    public static Doc docx(Path dir, String name) {
        String body = heading("Supplier handbook")
                + para("The notice period in the supplier contract is 45 days.")
                + para("The annual partner summit takes place in Gdansk every autumn.")
                + "<w:tbl>" + row("Product", "Price") + row("Falcon", "349") + row("Osprey", "512") + "</w:tbl>"
                + para("Prices exclude tax.");
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body + "</w:body></w:document>";
        String types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
        Path file = dir.resolve(name);
        try {
            Files.createDirectories(dir);
            try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
                entry(zip, "[Content_Types].xml", types);
                entry(zip, "_rels/.rels", rels);
                entry(zip, "word/document.xml", document);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Doc(file, DOCX_FACTS);
    }

    /** A file named {@code name} that claims to be a PDF but is not one. */
    public static Path corruptPdf(Path dir, String name) {
        return garbage(dir, name, "%PDF-1.7\nthis is not a pdf at all\n");
    }

    /** A file named {@code name} that claims to be a docx but is not a zip archive. */
    public static Path corruptDocx(Path dir, String name) {
        return garbage(dir, name, "PK-not-really-a-zip");
    }

    private static Path garbage(Path dir, String name, String content) {
        try {
            Files.createDirectories(dir);
            return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String heading(String text) {
        return "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    private static String para(String text) {
        return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    private static String row(String a, String b) {
        return "<w:tr><w:tc>" + para(a) + "</w:tc><w:tc>" + para(b) + "</w:tc></w:tr>";
    }
}
