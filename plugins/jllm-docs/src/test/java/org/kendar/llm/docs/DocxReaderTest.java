package org.kendar.llm.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.plugin.HarnessPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocxReaderTest {

    private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
            + " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\""
            + " xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\""
            + " xmlns:v=\"urn:schemas-microsoft-com:vml\""
            + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"";

    @TempDir
    Path dir;

    private Path docx(String body) throws IOException {
        Path f = dir.resolve("t.docx");
        try (OutputStream os = Files.newOutputStream(f); ZipOutputStream z = new ZipOutputStream(os)) {
            z.putNextEntry(new ZipEntry("word/document.xml"));
            z.write(("<?xml version=\"1.0\"?><w:document " + NS + "><w:body>" + body + "</w:body></w:document>")
                    .getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("word/header1.xml"));
            z.write(("<w:hdr " + NS + "><w:p><w:r><w:t>HEADER</w:t></w:r></w:p></w:hdr>").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return f;
    }

    private static String p(String text) {
        return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    @Test
    void readsHeadingsParagraphsListsAndTables() throws Exception {
        String body = "<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>"
                + p("Plain text")
                + "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/></w:numPr></w:pPr><w:r><w:t>item</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc>" + p("a") + "</w:tc><w:tc>" + p("b") + "</w:tc></w:tr>"
                + "<w:tr><w:tc>" + p("c") + "</w:tc><w:tc>" + p("d") + "</w:tc></w:tr></w:tbl>";

        String text = new DocxReader().extract(docx(body));

        assertEquals("## Title\nPlain text\n- item\na | b\nc | d\n", text);
        assertFalse(text.contains("HEADER"));
    }

    @Test
    void keepsInsertionsAndDropsDeletions() throws Exception {
        String body = "<w:p><w:r><w:t>keep </w:t></w:r><w:ins><w:r><w:t>added</w:t></w:r></w:ins>"
                + "<w:del><w:r><w:delText>removed</w:delText></w:r></w:del></w:p>";

        assertEquals("keep added\n", new DocxReader().extract(docx(body)));
    }

    @Test
    void textBoxKeepsOuterTextAndFallbackIsNotDuplicated() throws Exception {
        String box = "<w:txbxContent><w:p><w:r><w:t>in box</w:t></w:r></w:p></w:txbxContent>";
        String body = "<w:p><w:r><w:t>before </w:t></w:r><w:r><mc:AlternateContent>"
                + "<mc:Choice Requires=\"wps\"><w:drawing><wps:wsp><wps:txbx>" + box + "</wps:txbx></wps:wsp></w:drawing></mc:Choice>"
                + "<mc:Fallback><w:pict><v:shape><v:textbox>" + box + "</v:textbox></v:shape></w:pict></mc:Fallback>"
                + "</mc:AlternateContent></w:r><w:r><w:t>after</w:t></w:r></w:p>";

        assertEquals("in box\nbefore after\n", new DocxReader().extract(docx(body)));
    }

    @Test
    void tabStopsAreNotTabsButRunTabsAre() throws Exception {
        String body = "<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"720\"/></w:tabs></w:pPr>"
                + "<w:r><w:t>a</w:t><w:tab/><w:t>b</w:t></w:r></w:p>";

        assertEquals("a\tb\n", new DocxReader().extract(docx(body)));
    }

    @Test
    void drawingMlTextIsIgnored() throws Exception {
        String body = "<w:p><w:r><w:t>text</w:t><w:drawing><a:graphic><a:p><a:r><a:t>chart label</a:t></a:r></a:p>"
                + "</a:graphic></w:drawing></w:r></w:p>";

        assertEquals("text\n", new DocxReader().extract(docx(body)));
    }

    @Test
    void legacyDocAndGarbageAreRefused() throws Exception {
        Path doc = dir.resolve("old.doc");
        Files.write(doc, new byte[]{1, 2, 3});
        IOException e = assertThrows(IOException.class, () -> new DocxReader().extract(doc));
        assertEquals("legacy .doc not supported, convert to docx", e.getMessage());

        Path bad = dir.resolve("bad.docx");
        Files.writeString(bad, "nope");
        assertThrows(IOException.class, () -> new DocxReader().extract(bad));
    }

    @Test
    void pluginIsDiscoverable() {
        assertTrue(ServiceLoader.load(HarnessPlugin.class).stream().anyMatch(p -> p.type() == DocsPlugin.class));
    }
}
