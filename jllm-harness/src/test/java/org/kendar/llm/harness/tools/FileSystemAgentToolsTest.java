package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemAgentToolsTest {

    @TempDir
    Path root;

    @Test
    void jailAllowsPathsInsideWorkDir() {
        ToolContext ctx = new ToolContext("c1", root);

        Path p = FileSystemAgentTools.jail(ctx, "sub/a.txt");

        assertTrue(p.startsWith(root.toAbsolutePath().normalize()));
    }

    @Test
    void jailRejectsTraversal() {
        ToolContext ctx = new ToolContext("c1", root);

        assertThrows(IllegalArgumentException.class, () -> FileSystemAgentTools.jail(ctx, "../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> FileSystemAgentTools.jail(ctx, "a/../../outside.txt"));
    }

    @Test
    void lsListsDirectoriesFirstWithSizes() throws Exception {
        java.nio.file.Files.createDirectories(root.resolve("sub"));
        java.nio.file.Files.writeString(root.resolve("b.txt"), "12345");
        java.nio.file.Files.writeString(root.resolve("a.txt"), "1");
        var tool = new FileSystemAgentTools(null, null).tools().stream().filter(t -> t.name().equals("ls")).findFirst().get();
        String out = tool.execute(new ToolContext("c1", root), new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        assertEquals("d sub/\nf a.txt 1\nf b.txt 5", out);
    }
}
