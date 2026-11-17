package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalPrompterTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final List<String> said = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private final List<String> resolved = new ArrayList<>();

    private ApprovalPrompter prompter(String answer) {
        return new ApprovalPrompter(
                (chat, id, ok, edited, remember, dir) -> resolved.add(chat + "|" + id + "|" + ok + "|" + edited + "|"
                        + remember + "|" + dir),
                said::add, asked::add, () -> answer, Path.of("").toAbsolutePath());
    }

    private static ObjectNode req(String type) {
        return M.createObjectNode().put("id", "a1").put("type", type).put("title", "T").put("preview", "P");
    }

    @Test
    void toolCallAllowOnceAlwaysAndDeny() throws Exception {
        prompter("y").prompt("c", req("tool"));
        prompter("a").prompt("c", req("tool"));
        prompter("").prompt("c", req("tool"));
        prompter(LineCommands.EOF).prompt("c", req("tool"));
        assertEquals(List.of("c|a1|true|null|false|false", "c|a1|true|null|true|false",
                "c|a1|false|null|false|false", "c|a1|false|null|false|false"), resolved);
    }

    @Test
    void approvalTextIsCleanedAndShown() throws Exception {
        ObjectNode r = req("tool").put("title", "\u001b[2Jevil");
        prompter("n").prompt("c", r);
        assertTrue(said.get(0).contains("evil"));
        assertFalse(said.get(0).contains("\u001b[2J"));
        assertTrue(said.get(0).endsWith("P"));
    }

    @Test
    void writeDirOptionOnlyWhenOffered() throws Exception {
        prompter("d").prompt("c", req("tool"));
        assertTrue(resolved.get(0).endsWith("|false|false"), "no writeDir: d is not a grant");
        assertFalse(asked.get(0).contains("/d="));

        resolved.clear();
        asked.clear();
        ObjectNode r = req("tool").put("writeDir", "src");
        prompter("d").prompt("c", r);
        assertTrue(asked.get(0).contains("/d=allow writes in src/"));
        assertEquals("c|a1|true|null|false|true", resolved.get(0));
    }

    @Test
    void typedDirIsPassedAndBadOneWarned() throws Exception {
        ObjectNode r = req("tool").put("writeDir", "src");
        prompter("d lib/x").prompt("c", r);
        assertEquals("c|a1|true|lib/x|false|true", resolved.get(0));
        assertTrue(said.stream().noneMatch(s -> s.startsWith("[warning]")));

        prompter("d ../up").prompt("c", r);
        assertTrue(said.stream().anyMatch(s -> s.startsWith("[warning] Path not allowed")));
    }

    @Test
    void continueApprovalTakesTurnsOrStops() throws Exception {
        ObjectNode r = req("continue").put("proposed", "20");
        prompter("30").prompt("c", r);
        prompter("n").prompt("c", r);
        prompter("").prompt("c", r);
        prompter(LineCommands.EOF).prompt("c", r);
        assertTrue(asked.get(0).contains("[20, n = stop]"));
        assertEquals(List.of("c|a1|true|30|false|false", "c|a1|false|n|false|false", "c|a1|true|null|false|false",
                "c|a1|false|n|false|false"), resolved);
    }

    @Test
    void stuckAndInsecureCertAreYesNo() throws Exception {
        prompter("yes").prompt("c", req("stuck"));
        prompter("x").prompt("c", req("stuck"));
        prompter("y").prompt("c", req("insecure_cert").put("host", "h.example"));
        assertTrue(asked.get(2).contains("h.example"));
        assertEquals(List.of("c|a1|true|null|false|false", "c|a1|false|null|false|false",
                "c|a1|true|null|false|false"), resolved);
    }

    @Test
    void hostApprovalCanBeRememberedForTheChat() throws Exception {
        ObjectNode r = req("host").put("host", "h.example");
        prompter("y").prompt("c", r);
        prompter("a").prompt("c", r);
        prompter("n").prompt("c", r);
        assertEquals(List.of("c|a1|true|null|false|false", "c|a1|true|null|true|false",
                "c|a1|false|null|false|false"), resolved);
    }

    @Test
    void interruptedQuestionResolvesNothing() throws Exception {
        for (String t : new String[]{"tool", "continue", "stuck", "insecure_cert", "host"}) {
            prompter(LineCommands.INTERRUPTED).prompt("c", req(t));
        }
        assertTrue(resolved.isEmpty());
    }
}
