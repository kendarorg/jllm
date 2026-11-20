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
}
