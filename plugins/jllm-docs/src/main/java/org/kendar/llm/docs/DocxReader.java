package org.kendar.llm.docs;

import org.kendar.llm.harness.tools.DocumentReader;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads {@code word/document.xml} of a docx: paragraphs, headings ({@code #}), list items ({@code -}) and tables (rows as
 * {@code a | b | c}). Headers, footers and comments are skipped; tracked insertions are kept and deletions dropped.
 * Text-box paragraphs are emitted before the paragraph that anchors them; {@code mc:Fallback} copies are skipped.
 * A legacy {@code .doc} is refused.
 */
public final class DocxReader implements DocumentReader {

    /** The XML part is read up to this many bytes (guards against zip bombs). */
    static final long MAX_XML_BYTES = 100_000_000L;
    static final int MAX_CHARS = 5_000_000;

    @Override
    public Set<String> extensions() {
        return Set.of("docx", "doc");
    }

    @Override
    public String extract(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".doc")) throw new IOException("legacy .doc not supported, convert to docx");
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) throw new IOException("Not a docx file (no word/document.xml)");
            try (InputStream in = new LimitedInputStream(zip.getInputStream(entry), MAX_XML_BYTES)) {
                return parse(in);
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("Not a valid docx file: " + e.getMessage());
        } catch (XMLStreamException e) {
            throw new IOException("Cannot parse the docx: " + e.getMessage());
        }
    }

    /** WordprocessingML namespaces (transitional and strict). DrawingML ({@code a:p}, {@code a:t}) is not matched. */
    private static final Set<String> W_NS = Set.of("http://schemas.openxmlformats.org/wordprocessingml/2006/main",
            "http://purl.oclc.org/ooxml/wordprocessingml/main");
    private static final String MC_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    /** One open {@code w:p}; text-box paragraphs nest inside the paragraph that anchors them. */
    private static final class Para {
        final StringBuilder text = new StringBuilder();
        String style;
        boolean numbered;
        /** Open {@code w:r} of this paragraph (not of an enclosing one). */
        int runDepth;
    }

    static String parse(InputStream in) throws XMLStreamException, IOException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        XMLStreamReader r = f.createXMLStreamReader(in);
        StringBuilder out = new StringBuilder();
        Deque<Para> paras = new ArrayDeque<>();
        boolean inText = false;
        int fallbackDepth = 0;
        int tableDepth = 0;
        List<String> row = null;
        StringBuilder cell = null;
        long events = 0;
        while (r.hasNext() && out.length() < MAX_CHARS) {
            int ev = r.next();
            // The caller cancels a slow read by interrupting the thread.
            if ((++events & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("docx extraction interrupted");
            }
            if (fallbackDepth > 0) {
                // mc:Fallback repeats the mc:Choice content (e.g. a VML copy of a text box): skip it entirely.
                if (ev == XMLStreamConstants.START_ELEMENT) fallbackDepth++;
                else if (ev == XMLStreamConstants.END_ELEMENT) fallbackDepth--;
                continue;
            }
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if (MC_NS.equals(r.getNamespaceURI())) {
                    if (r.getLocalName().equals("Fallback")) fallbackDepth = 1;
                    continue;
                }
                if (!W_NS.contains(r.getNamespaceURI())) continue;
                Para para = paras.peek();
                switch (r.getLocalName()) {
                    case "p" -> paras.push(new Para());
                    case "pStyle" -> {
                        if (para != null) para.style = attr(r, "val");
                    }
                    case "numPr" -> {
                        if (para != null) para.numbered = true;
                    }
                    case "r" -> {
                        if (para != null) para.runDepth++;
                    }
                    case "t" -> inText = para != null && para.runDepth > 0;
                    // w:tab inside w:pPr/w:tabs is a tab-stop definition, not a tab character.
                    case "tab" -> {
                        if (para != null && para.runDepth > 0) para.text.append('\t');
                    }
                    case "br", "cr" -> {
                        if (para != null && para.runDepth > 0) para.text.append(' ');
                    }
                    case "tbl" -> tableDepth++;
                    case "tr" -> row = tableDepth == 1 ? new ArrayList<>() : row;
                    case "tc" -> cell = tableDepth == 1 ? new StringBuilder() : cell;
                    default -> {
                    }
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && inText) {
                Para para = paras.peek();
                if (para != null) para.text.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (!W_NS.contains(r.getNamespaceURI())) continue;
                switch (r.getLocalName()) {
                    case "t" -> inText = false;
                    case "r" -> {
                        Para para = paras.peek();
                        if (para != null && para.runDepth > 0) para.runDepth--;
                    }
                    case "p" -> {
                        Para para = paras.poll();
                        if (para == null) continue;
                        String text = para.text.toString();
                        if (tableDepth > 0) {
                            if (cell != null && !text.isBlank()) {
                                if (!cell.isEmpty()) cell.append(' ');
                                cell.append(text.strip());
                            }
                        } else if (!text.isBlank() || out.isEmpty() || out.charAt(out.length() - 1) != '\n') {
                            out.append(prefix(para.style, para.numbered)).append(text).append('\n');
                        }
                    }
                    case "tc" -> {
                        if (tableDepth == 1 && row != null && cell != null) row.add(cell.toString());
                    }
                    case "tr" -> {
                        if (tableDepth == 1 && row != null) {
                            out.append(String.join(" | ", row)).append('\n');
                            row = null;
                        }
                    }
                    case "tbl" -> tableDepth--;
                    default -> {
                    }
                }
            }
        }
        r.close();
        return out.toString();
    }

    private static String attr(XMLStreamReader r, String local) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals(local)) return r.getAttributeValue(i);
        }
        return null;
    }

    private static String prefix(String style, boolean numbered) {
        if (style != null) {
            String s = style.toLowerCase();
            if (s.startsWith("heading")) {
                String digits = s.replaceAll("\\D", "");
                int level = digits.isEmpty() ? 1 : Math.min(6, Integer.parseInt(digits));
                return "#".repeat(level) + " ";
            }
            if (s.equals("title")) return "# ";
            if (s.startsWith("listparagraph") || s.startsWith("list")) return "- ";
        }
        return numbered ? "- " : "";
    }

    /** Fails with an IOException once more than {@code limit} bytes were read. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long left;

        LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, (int) Math.min(len, left + 1));
            if (n > 0) {
                left -= n;
                if (left < 0) throw new IOException("The document is too large to read");
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
