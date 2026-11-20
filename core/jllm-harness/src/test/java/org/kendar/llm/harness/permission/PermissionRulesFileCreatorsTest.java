package org.kendar.llm.harness.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A rule on write_file also covers the tools that create files from a path: they cannot bypass it. */
class PermissionRulesFileCreatorsTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void denyingWriteFileHidesWebDownload() {
        PermissionRules rules = PermissionRules.parse(List.of("write_file"), null, null);
        assertTrue(rules.hides("web_download"));
        assertFalse(rules.hides("web_fetch"));
    }

    @Test
    void writeFilePathPatternAppliesToTheDownloadPath() {
        PermissionRules rules = PermissionRules.parse(List.of("write_file(.*\\.env)"), null, null);
        var env = M.createObjectNode().put("url", "https://example.com/x").put("path", "conf/.env");
        var ok = M.createObjectNode().put("url", "https://example.com/x.env").put("path", "ok.txt");
        assertEquals(PermissionRules.Kind.DENY, rules.match("web_download", env).kind());
        assertNull(rules.match("web_download", ok));
        assertFalse(rules.hides("web_download"));
    }

    @Test
    void writeFileDenyCoversShellWritesToTheSamePath() {
        PermissionRules rules = PermissionRules.parse(List.of("write_file(.*\\.env)"), null, null);
        assertEquals(PermissionRules.Kind.DENY,
                rules.match("bash", M.createObjectNode().put("command", "echo SECRET=1 > .env")).kind());
        assertEquals(PermissionRules.Kind.DENY,
                rules.match("bash", M.createObjectNode().put("command", "Set-Content -Path conf\\.env -Value x")).kind());
        assertNull(rules.match("bash", M.createObjectNode().put("command", "cat .env")));
        assertNull(rules.match("bash", M.createObjectNode().put("command", "echo fine > ok.txt")));
        assertFalse(rules.hides("bash"));
    }

    @Test
    void wholeWriteFileDenyCoversAnyShellWrite() {
        PermissionRules rules = PermissionRules.parse(List.of("edit_file"), null, null);
        assertEquals(PermissionRules.Kind.DENY,
                rules.match("bash", M.createObjectNode().put("command", "echo x > a.txt")).kind());
        assertNull(rules.match("bash", M.createObjectNode().put("command", "ls")));
    }

    @Test
    void askAndAllowRulesOnWriteFileDoNotReachTheShell() {
        PermissionRules rules = PermissionRules.parse(null, List.of("write_file(.*\\.env)"),
                List.of("write_file(.*\\.txt)"));
        assertNull(rules.match("bash", M.createObjectNode().put("command", "echo x > .env")));
        assertNull(rules.match("bash", M.createObjectNode().put("command", "echo x > a.txt")));
    }
}
