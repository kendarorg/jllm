package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.json;

class FileToolsLenientTest {

    @TempDir
    Path root;

    private String run(FileSystemAgentTools fs, String tool, String input) throws Exception {
        HarnessTool t = fs.tools().stream().filter(x -> x.name().equals(tool)).findFirst().orElseThrow();
        return t.execute(new ToolContext("c1", root), json(input));
    }

    private String run(String tool, String input) throws Exception {
        return run(new FileSystemAgentTools(), tool, input);
    }

    @Test
    void crlfFileKeepsItsLineEndings() throws Exception {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "one\r\ntwo\r\nthree\r\n");

        run("edit_file", "{\"path\":\"a.txt\",\"old_string\":\"two\\nthree\",\"new_string\":\"2\\n3\"}");

        assertEquals("one\r\n2\r\n3\r\n", Files.readString(f));
    }

    @Test
    void bomIsKept() throws Exception {
        Path f = root.resolve("b.txt");
        Files.write(f, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i', '\n'});

        run("edit_file", "{\"path\":\"b.txt\",\"old_string\":\"hi\",\"new_string\":\"ho\"}");

        byte[] b = Files.readAllBytes(f);
        assertEquals((byte) 0xEF, b[0]);
        assertEquals("ho\n", new String(b, 3, b.length - 3, StandardCharsets.UTF_8));
    }

    @Test
    void smartQuotesAndTrailingWhitespaceMatch() throws Exception {
        Path f = root.resolve("c.txt");
        Files.writeString(f, "say “hello”   \nnext\n");

        run("edit_file", "{\"path\":\"c.txt\",\"old_string\":\"say \\\"hello\\\"\",\"new_string\":\"say bye\"}");

        assertTrue(Files.readString(f).startsWith("say bye"));
        assertTrue(Files.readString(f).contains("next"));
    }

    @Test
    void ambiguousMatchFails() throws Exception {
        Path f = root.resolve("d.txt");
        Files.writeString(f, "x = 1  \nx = 1\n");

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> run("edit_file", "{\"path\":\"d.txt\",\"old_string\":\"x = 1\",\"new_string\":\"y\"}"));

        assertTrue(e.getMessage().contains("2 times"));
    }

    @Test
    void missReportsClosestLines() throws Exception {
        Files.writeString(root.resolve("e.txt"), "int count = 10;\nreturn count;\n");

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> run("edit_file", "{\"path\":\"e.txt\",\"old_string\":\"int cnt = 10;\",\"new_string\":\"x\"}"));

        assertTrue(e.getMessage().contains("Closest lines"));
        assertTrue(e.getMessage().contains("1: int count = 10;"));
    }

    @Test
    void multipleEditsAreAllOrNothing() throws Exception {
        Path f = root.resolve("f.txt");
        Files.writeString(f, "a\nb\nc\n");

        String out = run("edit_file",
                "{\"path\":\"f.txt\",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"A\"},{\"old_string\":\"c\",\"new_string\":\"C\"}]}");
        assertEquals("A\nb\nC\n", Files.readString(f));
        assertTrue(out.contains("- a"));
        assertTrue(out.contains("+ A"));

        assertThrows(IllegalArgumentException.class, () -> run("edit_file",
                "{\"path\":\"f.txt\",\"edits\":[{\"old_string\":\"b\",\"new_string\":\"B\"},{\"old_string\":\"zzz\",\"new_string\":\"Z\"}]}"));
        assertEquals("A\nb\nC\n", Files.readString(f));
    }

    @Test
    void appendKeepsCrlf() throws Exception {
        Path f = root.resolve("g.txt");
        Files.writeString(f, "a\r\nb\r\n");

        run("write_file", "{\"path\":\"g.txt\",\"content\":\"c\\nd\\n\",\"append\":true}");

        assertEquals("a\r\nb\r\nc\r\nd\r\n", Files.readString(f));
    }

    @Test
    void readFileCapsLinesAndTellsHowToContinue() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 2500; i++) sb.append("line").append(i).append('\n');
        Files.writeString(root.resolve("big.txt"), sb);

        String out = run("read_file", "{\"path\":\"big.txt\"}");

        assertTrue(out.contains("2000\tline2000"));
        assertFalse(out.contains("2001\tline2001"));
        assertTrue(out.contains("[showing lines 1-2000 of 2500; continue with offset=2001]"));
        assertTrue(run("read_file", "{\"path\":\"big.txt\",\"offset\":2001}").contains("2500\tline2500"));
    }

    @Test
    void documentReaderDispatchPagingAndMissingReader() throws Exception {
        Files.write(root.resolve("x.fake"), new byte[]{1, 2, 3});
        Files.write(root.resolve("y.pdf"), new byte[]{1, 2, 3});
        DocumentReaders readers = new DocumentReaders();
        readers.register(new DocumentReader() {
            public Set<String> extensions() {
                return Set.of("fake");
            }

            public String extract(Path file) {
                return "alpha\nbeta\ngamma";
            }
        });
        FileSystemAgentTools fs = new FileSystemAgentTools(readers);

        assertEquals("2\tbeta\n", run(fs, "read_file", "{\"path\":\"x.fake\",\"offset\":2,\"limit\":1}"));
        Exception e = assertThrows(IllegalArgumentException.class, () -> run(fs, "read_file", "{\"path\":\"y.pdf\"}"));
        assertEquals("No reader for .pdf; add the jllm-pdf module", e.getMessage());
    }
}
