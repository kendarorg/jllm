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

    @Test
    void plantedCheckpointPathsCannotLeaveTheWorkDir() throws Exception {
        Path work = Files.createDirectories(root.resolve("work"));
        Path victim = root.resolve("victim.txt");
        Files.writeString(victim, "keep");
        Files.writeString(work.resolve("own.txt"), "own");
        Files.createDirectories(work.resolve(".jllm/scratchpad"));
        Files.writeString(work.resolve(".jllm/scratchpad/s.txt"), "scratch");
        Path turn = Files.createDirectories(work.resolve(".jllm/checkpoints/c1/99999"));
        Files.writeString(turn.resolve("created.txt"), String.join("\n",
                "../victim.txt", victim.toAbsolutePath().toString(), "a/../../victim.txt",
                ".jllm/scratchpad/s.txt", "own.txt", ""));
        Path files = Files.createDirectories(turn.resolve("files"));
        Files.writeString(Files.createDirectories(files.resolve(".jllm/scratchpad")).resolve("s.txt"), "pwned");

        Checkpoints cp = new Checkpoints();
        var r = cp.undo("c1", work, 1);

        assertEquals(1, r.turns());
        assertEquals("keep", Files.readString(victim));
        assertEquals("scratch", Files.readString(work.resolve(".jllm/scratchpad/s.txt")));
        assertFalse(Files.exists(work.resolve("own.txt")), "a valid created file is still removed");
        assertEquals(java.util.List.of("own.txt"), r.deleted());
    }

    @Test
    void symlinksInCheckpointDataAreNotFollowed() throws Exception {
        Path work = Files.createDirectories(root.resolve("work"));
        Path secret = root.resolve("secret.txt");
        Files.writeString(secret, "TOP SECRET");
        Path files = Files.createDirectories(work.resolve(".jllm/checkpoints/c1/1/files"));
        try {
            Files.createSymbolicLink(files.resolve("copy.txt"), secret);
        } catch (Exception | Error e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks not permitted: " + e);
        }

        new Checkpoints().undo("c1", work, 1);

        assertFalse(Files.exists(work.resolve("copy.txt")), "a link must not copy an outside file in");
        assertEquals("TOP SECRET", Files.readString(secret));
    }

    @Test
    void toolsCannotWriteCheckpointDataButCanWriteTheScratchpad() throws Exception {
        FileSystemAgentTools fs = new FileSystemAgentTools(null, new Checkpoints());

        Exception e = assertThrows(IllegalArgumentException.class, () -> run(fs, "write_file",
                "{\"path\":\".jllm/checkpoints/c1/9/created.txt\",\"content\":\"../x\"}"));
        assertTrue(e.getMessage().contains("Checkpoint"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> run(fs, "write_file",
                "{\"path\":\"a/../.jllm/checkpoints/x\",\"content\":\"x\"}"));
        assertFalse(Files.exists(root.resolve(".jllm/checkpoints")));

        run(fs, "write_file", "{\"path\":\".jllm/scratchpad/notes.md\",\"content\":\"ok\"}");
        assertEquals("ok", Files.readString(root.resolve(".jllm/scratchpad/notes.md")));
    }

    @Test
    void oddTurnDirectoryNamesAreIgnored() throws Exception {
        Files.createDirectories(root.resolve(".jllm/checkpoints/c1/99999999999999999999999"));
        Files.createDirectories(root.resolve(".jllm/checkpoints/c1/abc"));
        Files.createDirectories(root.resolve(".jllm/checkpoints/c1/3000000000"));
        Checkpoints cp = new Checkpoints();
        FileSystemAgentTools fs = new FileSystemAgentTools(null, cp);
        Files.writeString(root.resolve("a.txt"), "one");

        cp.beginTurn("c1", root);
        run(fs, "write_file", "{\"path\":\"a.txt\",\"content\":\"two\"}");

        assertTrue(Files.isDirectory(root.resolve(".jllm/checkpoints/c1/3000000001")));
        var r = cp.undo("c1", root, 1);
        assertEquals(1, r.turns());
        assertEquals("one", Files.readString(root.resolve("a.txt")));
    }
}
