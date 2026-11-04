package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

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
}
