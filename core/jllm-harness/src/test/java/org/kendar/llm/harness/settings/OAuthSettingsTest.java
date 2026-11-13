package org.kendar.llm.harness.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The oauth block of REST APIs and the tokenStore section across settings layers. */
class OAuthSettingsTest {

    @TempDir
    Path tmp;

    private Path home;
    private Path project;
    private ProjectMcpTrust trust;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        home = tmp.resolve("home");
        project = tmp.resolve("proj");
        System.setProperty("jllm.home", home.toString());
        trust = new ProjectMcpTrust(home.resolve(".jllm/trusted-mcp.json"));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("jllm.home");
    }

    private void write(Path f, String s) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, s);
    }

    private HarnessSettings load(ProjectMcpTrust.Prompt prompt) {
        return HarnessSettings.loadLayered(null, project, Map.of("CRM_SECRET", "s3")::get, prompt, trust,
                warnings::add);
    }

    private static final String API = """
            {"restApis":[{"name":"crm","openApiUrl":"https://crm.example.com/openapi.json",
              "oauth":{"issuer":"https://login.example.com","clientId":"jllm","clientSecret":"%s","scopes":["r"],
                       "extraParams":{"audience":"x"}}}]}""";

    @Test
    void oauthBlockIsReadWithItsDefaults() throws Exception {
        write(home.resolve(".jllm/settings.json"), API.formatted("${CRM_SECRET}"));
        RestApiSettings.OAuth o = load(ProjectMcpTrust.Prompt.NONE).restApis().get(0).oauth();
        assertEquals("authorization_code", o.effectiveFlow());
        assertEquals("s3", o.clientSecret());
        assertTrue(o.effectivePerUser());
        assertEquals("Authorization", o.effectiveHeader());
        assertEquals("Bearer t", o.headerValue("t"));
        assertEquals(Map.of("audience", "x"), o.extraParams());
    }

    @Test
    void projectOauthNeedsTrustAndIsRememberedOnce() throws Exception {
        write(project.resolve(".jllm/settings.json"), API.formatted("${CRM_SECRET}"));
        assertTrue(load(ProjectMcpTrust.Prompt.NONE).restApis().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("crm") && w.contains("oauth")), warnings.toString());

        AtomicInteger asked = new AtomicInteger();
        List<String> shown = new ArrayList<>();
        ProjectMcpTrust.Prompt yes = (dir, name, line) -> {
            asked.incrementAndGet();
            shown.add(name + " " + line);
            return true;
        };
        assertEquals(1, load(yes).restApis().size());
        assertEquals(1, load(yes).restApis().size());
        assertEquals(1, asked.get());
        assertTrue(shown.get(0).startsWith("oauth:api:crm"), shown.toString());
        assertTrue(shown.get(0).contains("https://login.example.com"), shown.toString());
    }

    @Test
    void sameOauthApiInTheUserSettingsNeedsNoTrust() throws Exception {
        write(home.resolve(".jllm/settings.json"), API.formatted("${CRM_SECRET}"));
        write(project.resolve(".jllm/settings.json"), API.formatted("${CRM_SECRET}"));
        assertEquals(1, load(ProjectMcpTrust.Prompt.NONE).restApis().size());
    }

    @Test
    void literalSecretInTheProjectIsRefused() throws Exception {
        write(project.resolve(".jllm/settings.json"), API.formatted("literal-secret"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load((d, n, l) -> true));
        assertTrue(e.getMessage().contains("crm") && e.getMessage().contains("clientSecret"), e.getMessage());
        assertFalse(e.getMessage().contains("literal-secret"));
    }

    @Test
    void projectTokenStoreIsIgnored() throws Exception {
        write(home.resolve(".jllm/settings.json"), "{\"tokenStore\":{\"type\":\"file\"}}");
        write(project.resolve(".jllm/settings.json"), "{\"tokenStore\":{\"type\":\"native\",\"dir\":\"/tmp/x\",\"encrypt\":false}}");
        HarnessSettings s = load(ProjectMcpTrust.Prompt.NONE);
        assertEquals("file", s.tokenStore().type());
        assertNull(s.tokenStore().dir());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("tokenStore")), warnings.toString());

        Files.delete(home.resolve(".jllm/settings.json"));
        assertNull(load(ProjectMcpTrust.Prompt.NONE).tokenStore());
    }

    @Test
    void perUserIsRefusedOnAStdioServer() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new McpServerSettings("s",
                null, null, null, null, null, "cmd", null, null, true, null, null, true));
        assertTrue(e.getMessage().contains("perUser"));
        McpServerSettings http = new McpServerSettings("s", "http://x", null, null, null, null, null, null, null,
                true, null, null, null);
        assertTrue(http.perUserOAuth(true));
        assertFalse(http.perUserOAuth(false)); // a single user keeps one shared login, as before
    }
}
