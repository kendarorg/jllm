package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.json;

class CheckpointsTest {

    @TempDir
    Path root;

    private void run(FileSystemAgentTools fs, String tool, String input) throws Exception {
        fs.tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow()
                .execute(new ToolContext("c1", root), json(input));
    }

    @Test
    void undoRestoresEditedFilesAndRemovesCreatedOnes() throws Exception {
        Checkpoints cp = new Checkpoints();
        FileSystemAgentTools fs = new FileSystemAgentTools(null, cp);
        Files.writeString(root.resolve("a.txt"), "one\n");

        cp.beginTurn("c1", root);
        run(fs, "edit_file", "{\"path\":\"a.txt\",\"old_string\":\"one\",\"new_string\":\"two\"}");
        run(fs, "edit_file", "{\"path\":\"a.txt\",\"old_string\":\"two\",\"new_string\":\"three\"}");
        run(fs, "write_file", "{\"path\":\"sub/new.txt\",\"content\":\"x\"}");
        cp.beginTurn("c1", root);
        run(fs, "write_file", "{\"path\":\"a.txt\",\"content\":\"four\"}");

        var first = cp.undo("c1", root, 1);
        assertEquals(1, first.turns());
        assertEquals("three\n", Files.readString(root.resolve("a.txt")));
        assertTrue(Files.exists(root.resolve("sub/new.txt")));

        var second = cp.undo("c1", root, 1);
        assertEquals("one\n", Files.readString(root.resolve("a.txt")));
        assertFalse(Files.exists(root.resolve("sub/new.txt")));
        assertTrue(second.message().contains("shell commands are not restored"));
        assertEquals(0, cp.undo("c1", root, 1).turns());
    }
}
