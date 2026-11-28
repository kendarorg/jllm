package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.HarnessTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** In auto mode a write to configuration still needs a yes; with nobody to answer it is denied. */
class ProtectedWriteTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path base;

    private static String input(String path) {
        return M.createObjectNode().put("path", path).put("content", "x").toString();
    }

    private static JsonNode lastToolResult(Rig rig, int requestIdx) {
        ArrayNode m = rig.model.requests.get(requestIdx).messages();
        return m.get(m.size() - 1).get("content").get(0);
    }

    @Test
    void autoModeAsksForGitHooksAndDeniesWithoutAnApprover() throws Exception {
        Rig rig = new Rig(base);
        rig.register(new FileSystemAgentTools().tools().toArray(new HarnessTool[0]));
        String chatId = rig.newChat();
        rig.sessions.setMode(chatId, PermissionMode.AUTO);
        Path work = rig.sandbox.workDir(chatId);
        rig.model.enqueue(toolUse("w1", "write_file", input(".git/hooks/pre-commit")),
                toolUse("w2", "write_file", input("AGENTS.md")),
                toolUse("w3", "write_file", input("notes.txt")), text("done"));

        rig.loop.run(chatId, "write", new Events(), () -> false, null);

        assertFalse(Files.exists(work.resolve(".git/hooks/pre-commit")), "the hook was not written");
        assertFalse(Files.exists(work.resolve("AGENTS.md")), "the instruction file was not written");
        assertTrue(Files.exists(work.resolve("notes.txt")), "an ordinary file is still written in auto mode");
        assertTrue(lastToolResult(rig, 1).path("is_error").asBoolean(false));
    }
}
