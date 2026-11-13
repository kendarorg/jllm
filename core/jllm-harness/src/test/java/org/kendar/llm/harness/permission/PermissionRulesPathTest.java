package org.kendar.llm.harness.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Path arguments are normalized before the rules see them; rules without tool pattern are rejected. */
class PermissionRulesPathTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path work;

    private static ObjectNode path(String p) {
        return M.createObjectNode().put("path", p);
    }

    private static final PermissionRules RULES = PermissionRules.parse(List.of("write_file(^secret/)"), null, null);

    @Test
    void relativeTricksStillMatch() {
        for (String p : new String[]{"secret/a", "./secret/a", "x/../secret/a", "secret//a", ".\\secret\\a",
                "./x/./../secret/a"}) {
            assertNotNull(RULES.match("write_file", path(p), work, false), p);
        }
        assertNull(RULES.match("write_file", path("public/a"), work, false));
    }

    @Test
    void absolutePathsInsideTheWorkDirAreMadeRelative() {
        String abs = work.resolve("secret").resolve("a").toAbsolutePath().toString();
        assertNotNull(RULES.match("write_file", path(abs), work, false));
        assertNotNull(RULES.match("write_file", path(abs), work));
    }

    @Test
    void windowsFoldsCaseAndTrailingDots() {
        assertNotNull(RULES.match("write_file", path("SECRET/a"), work, true));
        assertNotNull(RULES.match("write_file", path("secret./a"), work, true));
        assertNotNull(RULES.match("write_file", path("Secret .\\a"), work, true));
        assertNull(RULES.match("write_file", path("SECRET/a"), work, false), "case matters elsewhere");
    }

    @Test
    void normalizationWithoutWorkDir() {
        assertEquals("secret/a", PermissionRules.normalizePath("./x/../secret//a", null, false));
        assertEquals("/etc/passwd", PermissionRules.normalizePath("/tmp/../etc/./passwd", null, false));
        assertEquals("../up", PermissionRules.normalizePath("../up", null, false));
        assertNotNull(RULES.match("write_file", path("./secret/a"), null));
    }

    @Test
    void ruleWithoutToolPatternIsIgnored() {
        PermissionRules r = PermissionRules.parse(List.of("(rm -rf)", "bash(rm)"), null, null);
        assertEquals(1, r.describe().size());
        assertEquals("deny  bash(rm)", r.describe().get(0));
    }
}
