package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTrustFilterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path PROJECT = Path.of("project");

    /** Remembers answers in memory, keyed by name and definition. */
    private static final class MemoryTrust implements ProjectMcpTrust {
        final Map<String, Boolean> answers = new HashMap<>();

        @Override
        public Boolean lookup(Path projectDir, String server, JsonNode definition) {
            return answers.get(server + "|" + ProjectMcpTrust.fingerprint(definition));
        }

        @Override
        public void remember(Path projectDir, String server, JsonNode definition, boolean trusted) {
            answers.put(server + "|" + ProjectMcpTrust.fingerprint(definition), trusted);
        }
    }

    private static ObjectNode json(String text) throws Exception {
        return (ObjectNode) MAPPER.readTree(text);
    }

    @Test
    void projectOnlyStdioServerIsDroppedWithoutAnAnswer() throws Exception {
        ObjectNode project = json("{\"mcpServers\":[{\"name\":\"s\",\"command\":\"node\",\"args\":[\"x.js\"]}]}");
        ObjectNode merged = project.deepCopy();
        List<String> warnings = new ArrayList<>();

        ProjectTrustFilter.apply(merged, project, List.of(), PROJECT, ProjectMcpTrust.Prompt.NONE,
                new MemoryTrust(), warnings::add);

        assertEquals(0, merged.get("mcpServers").size());
        assertTrue(warnings.get(0).contains("never trusted"), warnings.toString());
    }

    @Test
    void anAnswerIsRememberedAndAskedOnlyOnce() throws Exception {
        ObjectNode project = json("{\"mcpServers\":[{\"name\":\"s\",\"command\":\"node\",\"args\":[\"x.js\"]}]}");
        MemoryTrust trust = new MemoryTrust();
        List<String> asked = new ArrayList<>();
        ProjectMcpTrust.Prompt yes = (dir, name, line) -> {
            asked.add(line);
            return true;
        };

        ObjectNode first = project.deepCopy();
        ProjectTrustFilter.apply(first, project, List.of(), PROJECT, yes, trust, w -> { });
        ObjectNode second = project.deepCopy();
        ProjectTrustFilter.apply(second, project, List.of(), PROJECT, ProjectMcpTrust.Prompt.NONE, trust, w -> { });

        assertEquals(1, first.get("mcpServers").size());
        assertEquals(1, second.get("mcpServers").size());
        assertEquals(List.of("node x.js"), asked);
    }

    @Test
    void aServerAlsoDefinedByAnotherLayerCountsAsTheUsersOwn() throws Exception {
        ObjectNode project = json("{\"mcpServers\":[{\"name\":\"s\",\"command\":\"node\"}]}");
        ObjectNode user = project.deepCopy();
        ObjectNode merged = project.deepCopy();

        ProjectTrustFilter.apply(merged, project, List.of(user), PROJECT, ProjectMcpTrust.Prompt.NONE,
                new MemoryTrust(), w -> fail(w));

        assertEquals(1, merged.get("mcpServers").size());
    }

    @Test
    void stopHookUsesItsPrefixAndIsDroppedWhenRefused() throws Exception {
        ObjectNode project = json("{\"hooks\":{\"stop\":[{\"command\":\"make test\"}]}}");
        ObjectNode merged = project.deepCopy();
        List<String> names = new ArrayList<>();

        ProjectTrustFilter.apply(merged, project, List.of(), PROJECT, (dir, name, line) -> {
            names.add(name);
            return false;
        }, new MemoryTrust(), w -> { });

        assertEquals(0, merged.get("hooks").get("stop").size());
        assertEquals(List.of(ProjectTrustFilter.STOP_HOOK_TRUST_PREFIX + "make test"), names);
    }

    @Test
    void oauthRestApiOfTheProjectIsDroppedUnlessTrusted() throws Exception {
        ObjectNode project = json("{\"restApis\":[{\"name\":\"crm\",\"baseUrl\":\"http://x\","
                + "\"oauth\":{\"tokenUrl\":\"http://t\"}},{\"name\":\"plain\",\"baseUrl\":\"http://y\"}]}");
        ObjectNode merged = project.deepCopy();

        ProjectTrustFilter.apply(merged, project, List.of(), PROJECT, ProjectMcpTrust.Prompt.NONE,
                new MemoryTrust(), w -> { });

        assertEquals(1, merged.get("restApis").size());
        assertEquals("plain", merged.get("restApis").get(0).get("name").asText());
    }

    @Test
    void literalClientSecretInTheProjectIsRefusedButEnvReferenceIsFine() throws Exception {
        ObjectNode literal = json("{\"restApis\":[{\"name\":\"crm\",\"oauth\":{\"clientSecret\":\"hunter2\"}}]}");
        ObjectNode env = json("{\"restApis\":[{\"name\":\"crm\",\"oauth\":{\"clientSecret\":\"${CRM_SECRET}\"}}]}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProjectTrustFilter.refuseLiteralSecrets(literal));
        assertTrue(e.getMessage().contains("crm"), e.getMessage());
        ProjectTrustFilter.refuseLiteralSecrets(env);
    }

    @Test
    void projectTokenStoreIsIgnoredAndTheUsersIsKept() throws Exception {
        ObjectNode project = json("{\"tokenStore\":{\"type\":\"file\",\"dir\":\"/evil\"}}");
        ObjectNode user = json("{\"tokenStore\":{\"type\":\"native\"}}");
        ObjectNode merged = json("{\"tokenStore\":{\"type\":\"file\",\"dir\":\"/evil\"}}");
        List<String> warnings = new ArrayList<>();

        ProjectTrustFilter.projectTokenStore(merged, project, List.of(user), warnings::add);

        assertEquals("native", merged.get("tokenStore").get("type").asText());
        assertFalse(merged.get("tokenStore").has("dir"));
        assertEquals(1, warnings.size());

        ObjectNode alone = project.deepCopy();
        ProjectTrustFilter.projectTokenStore(alone, project, List.of(), w -> { });
        assertFalse(alone.has("tokenStore"));
    }

    private static void fail(String message) {
        org.junit.jupiter.api.Assertions.fail(message);
    }
}
