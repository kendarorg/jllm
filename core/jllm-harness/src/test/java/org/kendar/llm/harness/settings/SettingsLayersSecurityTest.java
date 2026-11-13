package org.kendar.llm.harness.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Project settings cannot drop global denials, bad roots are ignored, project stdio MCP servers need trust. */
class SettingsLayersSecurityTest {

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
        trust = new FileProjectMcpTrust(home.resolve(".jllm/trusted-mcp.json"));
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
        return HarnessSettings.loadLayered(new FileSettingsSource(null, project), project, k -> null, prompt, trust, warnings::add);
    }

    @Test
    void denyListsAreUnited() throws Exception {
        write(home.resolve(".jllm/settings.json"), "{\"permissions\":{\"deny\":[\"bash(rm)\"],\"allow\":[\"a\"]}}");
        write(project.resolve(".jllm/settings.json"), "{\"permissions\":{\"deny\":[\"web_fetch\"],\"allow\":[]}}");
        HarnessSettings s = load(ProjectMcpTrust.Prompt.NONE);
        assertEquals(List.of("bash(rm)", "web_fetch"), s.permissions().deny());
        assertEquals(List.of(), s.permissions().allow(), "other arrays are still replaced");
    }

    @Test
    void nonObjectRootsAreIgnored() throws Exception {
        write(home.resolve(".jllm/settings.json"), "{\"smallModel\":true,\"permissions\":{\"deny\":[\"x\"]}}");
        for (String bad : new String[]{"null", "[1,2]", "\"text\"", ""}) {
            write(project.resolve(".jllm/settings.json"), bad);
            warnings.clear();
            HarnessSettings s = load(ProjectMcpTrust.Prompt.NONE);
            assertTrue(s.isSmallModel(), bad);
            assertEquals(List.of("x"), s.permissions().deny(), bad);
            assertTrue(warnings.stream().anyMatch(w -> w.contains("ignored")), bad + " " + warnings);
        }
    }

    @Test
    void unknownPropertiesAreReported() throws Exception {
        write(project.resolve(".jllm/settings.json"), "{\"smallModle\":true,\"context\":{\"maxToken\":5}}");
        load(ProjectMcpTrust.Prompt.NONE);
        assertTrue(warnings.contains("Unknown setting 'smallModle' at /smallModle (ignored)"), warnings.toString());
        assertTrue(warnings.contains("Unknown setting 'maxToken' at /context/maxToken (ignored)"), warnings.toString());
        warnings.clear();
        Path single = tmp.resolve("single.json");
        write(single, "{\"bogus\":1}");
        HarnessSettings.mapper(warnings::add).treeToValue(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(single.toFile()), HarnessSettings.class);
        assertEquals(List.of("Unknown setting 'bogus' at /bogus (ignored)"), warnings);
    }

    private static final String STDIO = "{\"mcpServers\":[{\"name\":\"evil\",\"command\":\"sh\",\"args\":[\"-c\",\"x\"]},"
            + "{\"name\":\"web\",\"url\":\"http://localhost:1/mcp\"}]}";

    @Test
    void projectStdioServerIsSkippedWithoutAPrompt() throws Exception {
        write(project.resolve(".jllm/settings.json"), STDIO);
        HarnessSettings s = load(ProjectMcpTrust.Prompt.NONE);
        assertEquals(List.of("web"), s.mcpServers().stream().map(McpServerSettings::name).toList());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'evil'")), warnings.toString());
    }

    @Test
    void projectStdioServerIsAskedOnceAndRemembered() throws Exception {
        write(project.resolve(".jllm/settings.json"), STDIO);
        AtomicInteger asked = new AtomicInteger();
        List<String> lines = new ArrayList<>();
        HarnessSettings s = load((dir, name, line) -> {
            asked.incrementAndGet();
            lines.add(name + ": " + line);
            return true;
        });
        assertEquals(2, s.mcpServers().size());
        assertEquals(List.of("evil: sh -c x"), lines);
        // remembered: no prompt needed any more (also where no one can be asked)
        assertEquals(2, load(ProjectMcpTrust.Prompt.NONE).mcpServers().size());
        assertEquals(1, asked.get());
        // a changed definition asks again
        write(project.resolve(".jllm/settings.json"), STDIO.replace("\"x\"", "\"y\""));
        assertEquals(1, load(ProjectMcpTrust.Prompt.NONE).mcpServers().size());
        assertEquals(1, load((d, n, l) -> false).mcpServers().size());
        assertEquals(1, load((d, n, l) -> { throw new AssertionError("refusal is remembered"); }).mcpServers().size());
    }

    @Test
    void stdioServersOfTheUserSettingsNeedNoTrust() throws Exception {
        write(home.resolve(".jllm/settings.json"), STDIO);
        HarnessSettings s = load((d, n, l) -> { throw new AssertionError("not asked"); });
        assertEquals(2, s.mcpServers().size());
        Path explicit = tmp.resolve("explicit.json");
        write(explicit, STDIO);
        write(project.resolve(".jllm/settings.json"), "{}");
        assertEquals(2, HarnessSettings.loadLayered(new FileSettingsSource(explicit, project), project, k -> null, ProjectMcpTrust.Prompt.NONE, trust,
                warnings::add).mcpServers().size());
    }
}
