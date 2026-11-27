package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LooseningTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;

    private ObjectNode json(String s) throws Exception {
        return (ObjectNode) M.readTree(s);
    }

    private Path project() throws Exception {
        Path p = Files.createDirectories(tmp.resolve("project/.jllm")).getParent();
        Files.writeString(p.resolve(".jllm/settings.json"), "{}");
        return p;
    }

    /** Applies the filter as the loader does: merged = user then project. */
    private ObjectNode run(String user, String project, Path dir, FolderTrust trust, FolderTrust.Prompt prompt,
                           List<String> warnings) throws Exception {
        ObjectNode u = json(user);
        ObjectNode p = json(project);
        ObjectNode merged = (ObjectNode) HarnessSettingsLoader.merge(u.deepCopy(), p.deepCopy());
        ProjectTrustFilter.apply(merged, p, List.of(u), dir, prompt, trust, warnings::add);
        return merged;
    }

    @Test
    void unapprovedLoosenessIsDroppedWhenNobodyCanAsk() throws Exception {
        Path dir = project();
        FileFolderTrust t = new FileFolderTrust(tmp.resolve("trusted.json"));
        t.trust(dir, Set.of());
        List<String> w = new ArrayList<>();
        ObjectNode m = run("{\"permissions\":{\"allow\":[\"Read\"],\"ask\":[\"Bash\"]},\"sandbox\":{\"backend\":\"docker\"}}",
                "{\"permissions\":{\"allow\":[\"Read\",\"Bash(*)\"],\"ask\":[]},\"sandbox\":{\"backend\":\"local\"},"
                        + "\"output\":{\"taintGating\":false,\"sanitize\":\"off\"},\"web\":{\"blockLocalHost\":false},"
                        + "\"whitelistedEnvVars\":[\"PATH\"],\"toolsWhitelistedEnvVars\":[\"HOME\"]}",
                dir, t, FolderTrust.Prompt.NONE, w);
        assertEquals(1, m.at("/permissions/allow").size());
        assertEquals("Bash", m.at("/permissions/ask/0").asText());
        assertEquals("docker", m.at("/sandbox/backend").asText());
        assertFalse(m.at("/output/taintGating").isBoolean());
        assertFalse(m.at("/output/sanitize").isTextual());
        assertFalse(m.at("/web/blockLocalHost").isBoolean());
        assertTrue(w.size() >= 5, w.toString());
    }

    @Test
    void approvedLoosenessApplies() throws Exception {
        Path dir = project();
        FileFolderTrust t = new FileFolderTrust(tmp.resolve("trusted.json"));
        t.trust(dir, Set.of("permissions.allow", "sandbox.backend"));
        ObjectNode m = run("{\"sandbox\":{\"backend\":\"docker\"}}",
                "{\"permissions\":{\"allow\":[\"Bash(*)\"]},\"sandbox\":{\"backend\":\"local\"}}", dir, t,
                FolderTrust.Prompt.NONE, new ArrayList<>());
        assertEquals("Bash(*)", m.at("/permissions/allow/0").asText());
        assertEquals("local", m.at("/sandbox/backend").asText());
    }

    @Test
    void changedHashAsksAgain() throws Exception {
        Path dir = project();
        FileFolderTrust t = new FileFolderTrust(tmp.resolve("trusted.json"));
        t.trust(dir, Set.of("permissions.allow"));
        Files.writeString(dir.resolve(".jllm/settings.json"), "{\"x\":1}");
        ObjectNode m = run("{}", "{\"permissions\":{\"allow\":[\"Bash(*)\"]}}", dir, t, FolderTrust.Prompt.NONE,
                new ArrayList<>());
        assertFalse(m.at("/permissions/allow").isArray());
    }

    @Test
    void tighteningAppliesSilentlyAndYesIsRemembered() throws Exception {
        Path dir = project();
        FileFolderTrust t = new FileFolderTrust(tmp.resolve("trusted.json"));
        t.trust(dir, Set.of());
        List<String> asked = new ArrayList<>();
        ObjectNode m = run("{\"permissions\":{\"allow\":[\"Read\",\"Write\"]},\"sandbox\":{\"backend\":\"local\"}}",
                "{\"permissions\":{\"allow\":[\"Read\"]},\"sandbox\":{\"backend\":\"docker\"},"
                        + "\"output\":{\"taintGating\":true}}", dir, t,
                (d, n, c) -> {
                    asked.add(n);
                    return true;
                }, new ArrayList<>());
        assertTrue(asked.isEmpty(), asked.toString());
        assertEquals("docker", m.at("/sandbox/backend").asText());
        ObjectNode m2 = run("{}", "{\"web\":{\"fetchMaxBytes\":999999999}}", dir, t, (d, n, c) -> {
            asked.add(n);
            assertTrue(c.contains("web.fetchMaxBytes"), "the diff is shown: " + c);
            return true;
        }, new ArrayList<>());
        assertEquals(List.of("loosen:web.fetchMaxBytes"), asked);
        assertEquals(999999999L, m2.at("/web/fetchMaxBytes").asLong());
        assertTrue(t.record(dir).orElseThrow().loosenings().contains("web.fetchMaxBytes"));
        // declining is not remembered
        ObjectNode m3 = run("{}", "{\"sandbox\":{\"timeoutSeconds\":99999}}", dir, t, (d, n, c) -> false,
                new ArrayList<>());
        assertFalse(m3.at("/sandbox/timeoutSeconds").isNumber());
        assertFalse(t.record(dir).orElseThrow().loosenings().contains("sandbox.timeoutSeconds"));
    }

    @Test
    void projectCannotSetTrustedProjects() throws Exception {
        Path dir = project();
        ObjectNode m = run("{}", "{\"trustedProjects\":[\"/\"]}", dir, FolderTrust.none(), FolderTrust.Prompt.NONE,
                new ArrayList<>());
        assertFalse(m.has("trustedProjects"));
    }

    private static final String USER_LOOSE = "{\"whitelistedEnvVars\":[\"PATH\"],\"toolsWhitelistedEnvVars\":[\"PATH\"],"
            + "\"sandbox\":{\"timeoutSeconds\":30,\"localFallback\":false,\"docker\":{\"image\":\"safe:1\"}},"
            + "\"web\":{\"fetchMaxBytes\":1000},\"output\":{\"taintDocuments\":true}}";
    private static final String PROJECT_LOOSE = "{\"whitelistedEnvVars\":[\"PATH\",\"EXTRA_A\"],"
            + "\"toolsWhitelistedEnvVars\":[\"PATH\",\"EXTRA_B\"],"
            + "\"sandbox\":{\"timeoutSeconds\":99999,\"localFallback\":true,\"docker\":{\"image\":\"evil:1\"}},"
            + "\"web\":{\"fetchMaxBytes\":999999999},\"output\":{\"taintDocuments\":false}}";

    @Test
    void environmentSandboxWebAndTaintDocumentsLoosenessIsDroppedUnapproved() throws Exception {
        Path dir = project();
        FileFolderTrust t = new FileFolderTrust(tmp.resolve("trusted.json"));
        t.trust(dir, Set.of());
        List<String> w = new ArrayList<>();
        ObjectNode m = run(USER_LOOSE, PROJECT_LOOSE, dir, t, FolderTrust.Prompt.NONE, w);
        assertEquals(1, m.at("/whitelistedEnvVars").size(), m.toString());
        assertEquals(1, m.at("/toolsWhitelistedEnvVars").size(), m.toString());
        assertEquals(30, m.at("/sandbox/timeoutSeconds").asInt());
        assertFalse(m.at("/sandbox/localFallback").asBoolean());
        assertEquals("safe:1", m.at("/sandbox/docker/image").asText());
        assertEquals(1000, m.at("/web/fetchMaxBytes").asInt());
        assertTrue(m.at("/output/taintDocuments").asBoolean());
        assertEquals(7, w.size(), w.toString());
    }

    @Test
    void environmentSandboxWebAndTaintDocumentsLoosenessAppliesWhenApproved() throws Exception {
        Path dir = project();
        FileFolderTrust t = new FileFolderTrust(tmp.resolve("trusted.json"));
        t.trust(dir, Set.of("whitelistedEnvVars", "toolsWhitelistedEnvVars", "sandbox.timeoutSeconds",
                "sandbox.localFallback", "sandbox.docker.image", "web.fetchMaxBytes", "output.taintDocuments"));
        ObjectNode m = run(USER_LOOSE, PROJECT_LOOSE, dir, t, FolderTrust.Prompt.NONE, new ArrayList<>());
        assertTrue(m.at("/whitelistedEnvVars").toString().contains("EXTRA_A"), m.toString());
        assertTrue(m.at("/toolsWhitelistedEnvVars").toString().contains("EXTRA_B"), m.toString());
        assertEquals(99999, m.at("/sandbox/timeoutSeconds").asInt());
        assertTrue(m.at("/sandbox/localFallback").asBoolean());
        assertEquals("evil:1", m.at("/sandbox/docker/image").asText());
        assertEquals(999999999, m.at("/web/fetchMaxBytes").asInt());
        assertFalse(m.at("/output/taintDocuments").asBoolean());
    }
}
