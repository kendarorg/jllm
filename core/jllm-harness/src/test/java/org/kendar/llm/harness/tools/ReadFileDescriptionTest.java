package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReadFileDescriptionTest {

    private static DocumentReader reader(String... ext) {
        return new DocumentReader() {
            public Set<String> extensions() { return Set.of(ext); }
            public String extract(java.io.InputStream in, String fileName) { return ""; }
        };
    }

    private static String description(DocumentReaders readers) {
        return new FileSystemAgentTools(readers, null).tools().stream()
                .filter(t -> t.name().equals("read_file")).findFirst().orElseThrow().description();
    }

    @Test
    void withoutReadersOnlyTextFilesAreNamed() {
        assertEquals("Read a text file from the chat work dir. Optional 1-based offset and line limit.",
                description(new DocumentReaders()));
        assertFalse(description(null).contains("Also reads"));
    }

    @Test
    void registeredDocumentFormatsAreNamedSorted() {
        DocumentReaders readers = new DocumentReaders();
        readers.register(reader("pdf"));
        readers.register(reader("DOCX"));
        assertTrue(description(readers).endsWith(" Also reads .docx, .pdf files: returns their text."),
                description(readers));
    }
}
