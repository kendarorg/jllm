package org.kendar.llm.harness.permission;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class PermissionRulesTest {

    @TempDir
    Path base;

    AtomicInteger runs = new AtomicInteger();

    private Rig rig(List<String> deny, List<String> ask, List<String> allow) {
        Rig rig = new Rig(base);
        rig.register(tool("bash", "Shell", ToolKind.OTHER, (c, in) -> {
            runs.incrementAndGet();
            return "ran";
        }), tool("write_file", "Files", ToolKind.FILE_EDIT, (c, in) -> {
            runs.incrementAndGet();
            return "wrote";
        }));
        rig.requiresApproval.put("bash", true);
        rig.requiresApproval.put("write_file", true);
        rig.rules(PermissionRules.parse(deny, ask, allow));
        return rig;
    }

    private String lastResult(Rig rig, int requestIdx) {
        var m = rig.model.requests.get(requestIdx).messages();
        return m.get(m.size() - 1).get("content").get(0).get("content").asText();
    }

    @Test
    void denyBeatsAutoModeAndAChatGrant() {
        Rig rig = rig(List.of("bash(.*git push.*)"), null, null);
        String chat = rig.newChat();
        rig.sessions.setMode(chat, PermissionMode.AUTO);
        rig.approvals.grant(chat, "bash");
        rig.model.enqueue(toolUse("t1", "bash", "{\"command\":\"git push origin main\"}"), text("ok"));

        rig.loop.run(chat, "go", new Events(), () -> false);

        assertEquals(0, runs.get());
        assertEquals("Denied by permission rule: bash(.*git push.*)", lastResult(rig, 1));
    }

    @Test
    void denyOnlyMatchesTheArgumentPattern() {
        Rig rig = rig(List.of("bash(rm -rf .*)"), null, null);
        String chat = rig.newChat();
        rig.sessions.setMode(chat, PermissionMode.AUTO);
        rig.model.enqueue(toolUse("t1", "bash", "{\"command\":\"ls\"}"), text("ok"));

        rig.loop.run(chat, "go", new Events(), () -> false);

        assertEquals(1, runs.get());
    }

    @Test
    void pathRuleMatchesBothSeparators() {
        PermissionRules rules = PermissionRules.parse(List.of("write_file(.*/secrets/.*)"), null, null);
        JsonNode win = json("{\"path\":\"a\\\\secrets\\\\k.txt\"}");
        JsonNode unix = json("{\"path\":\"a/secrets/k.txt\"}");

        assertEquals(PermissionRules.Kind.DENY, rules.match("write_file", win).kind());
        assertEquals(PermissionRules.Kind.DENY, rules.match("write_file", unix).kind());
        assertNull(rules.match("write_file", json("{\"path\":\"a/b.txt\"}")));
    }

    @Test
    void invalidRegexFailsAtStartupWithTheIndex() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new HarnessSettings(null, null, null, null, null, null, null, null, null, null, null,
                        new HarnessSettings.Permissions(List.of("ok", "bash(["), null, null), null, null));

        assertTrue(e.getMessage().contains("permissions.deny[1]"), e.getMessage());
    }

    @Test
    void bareToolNameRuleHidesTheTool() {
        PermissionRules rules = PermissionRules.parse(List.of("mcp__docs__.*", "bash(rm .*)"), null, null);

        assertTrue(rules.hides("mcp__docs__search"));
        assertFalse(rules.hides("bash"));
    }

    @Test
    void allowSkipsTheQuestionButAskForcesIt() {
        Rig rig = rig(null, List.of("write_file"), List.of("bash(mvn .*)"));
        String chat = rig.newChat();
        rig.model.enqueue(toolUse("t1", "bash", "{\"command\":\"mvn test\"}"), text("ok"));
        Events ev = new Events();

        rig.loop.run(chat, "go", ev, () -> false);

        assertEquals(1, runs.get());
        assertTrue(ev.ofType("approval_request").isEmpty());

        rig.model.enqueue(toolUse("t2", "bash", "{\"command\":\"curl x\"}"), text("ok"));
        Events ev2 = new Events();
        ev2.also = (type, data) -> {
            if ("approval_request".equals(type)) rig.approvals.resolve(chat, data.get("id").asText(), false, null);
        };
        rig.loop.run(chat, "again", ev2, () -> false);
        assertEquals(1, ev2.ofType("approval_request").size());
        assertEquals(1, runs.get());
    }

    @Test
    void firstMatchWinsInDenyAskAllowOrder() {
        PermissionRules rules = PermissionRules.parse(List.of("bash(.*curl.*)"), List.of("bash(.*)"),
                List.of("bash(mvn .*)"));

        assertEquals(PermissionRules.Kind.DENY, rules.match("bash", json("{\"command\":\"curl mvn x\"}")).kind());
        assertEquals(PermissionRules.Kind.ASK, rules.match("bash", json("{\"command\":\"mvn test\"}")).kind());
    }
}
