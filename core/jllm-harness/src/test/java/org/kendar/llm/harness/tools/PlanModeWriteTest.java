package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.json;

/** In plan mode a file write goes to the scratch dir: the result says so and it is not checkpointed. */
class PlanModeWriteTest {

    @TempDir
    Path root;

    private static String run(FileSystemAgentTools fs, ToolContext ctx, String tool, String input) throws Exception {
        return fs.tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow()
                .execute(ctx, json(input));
    }

    @Test
    void theResultSaysTheProjectFileWasNotCreatedAndNothingIsCheckpointed() throws Exception {
        Checkpoints cp = new Checkpoints();
        FileSystemAgentTools fs = new FileSystemAgentTools(null, cp);
        Path scratch = Files.createDirectories(root.resolve("jllm-agent"));
        ToolContext plan = new ToolContext("c1", root, scratch, true, "t1");
        cp.beginTurn("c1", root);

        String result = run(fs, plan, "write_file", "{\"path\":\"blocked.txt\",\"content\":\"x\"}");

        // the outcome comes first: a model that reads only the opening must not report the file as created
        assertTrue(result.startsWith("Plan mode: blocked.txt was NOT created or changed in the project"), result);
        assertTrue(result.contains("Wrote 1 chars to its copy in the scratch directory"), result);
        assertTrue(Files.exists(scratch.resolve("blocked.txt")));
        assertFalse(Files.exists(root.resolve("blocked.txt")), "the working directory is untouched");
        Path checkpoints = root.resolve(".jllm/checkpoints");
        if (Files.exists(checkpoints)) {
            try (Stream<Path> s = Files.walk(checkpoints)) {
                assertTrue(s.noneMatch(p -> p.getFileName().toString().equals("created.txt")),
                        "a scratch file is not recorded for undo");
            }
        }

        String edited = run(fs, plan, "edit_file",
                "{\"path\":\"blocked.txt\",\"old_string\":\"x\",\"new_string\":\"y\"}");
        assertTrue(edited.startsWith("Plan mode: blocked.txt was NOT created or changed in the project"), edited);
        assertTrue(edited.contains("Edited its copy in the scratch directory"), edited);
        assertTrue(edited.contains("(1 replacement(s))"), edited);
    }

    @Test
    void outsidePlanModeTheResultIsUnchangedAndTheFileIsCheckpointed() throws Exception {
        Checkpoints cp = new Checkpoints();
        FileSystemAgentTools fs = new FileSystemAgentTools(null, cp);
        Path scratch = Files.createDirectories(root.resolve("jllm-agent"));
        ToolContext edit = new ToolContext("c1", root, scratch, false, "t1");
        cp.beginTurn("c1", root);

        String result = run(fs, edit, "write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");

        assertEquals("Wrote 1 chars to a.txt", result);
        assertTrue(Files.exists(root.resolve("a.txt")));
    }
}
