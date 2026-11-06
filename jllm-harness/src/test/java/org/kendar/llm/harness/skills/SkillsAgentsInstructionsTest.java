package org.kendar.llm.harness.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentCatalog;
import org.kendar.llm.harness.agent.AgentDefinition;
import org.kendar.llm.harness.agent.AgentLoop;
import org.kendar.llm.harness.agent.SubAgentTool;
import org.kendar.llm.harness.instructions.InstructionLoader;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.SkillSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SkillsAgentsInstructionsTest {

    @TempDir
    Path base;

    private Path write(String name, String content) throws Exception {
        return Files.writeString(base.resolve(name), content);
    }

    @Test
    void skillToolListsLoadsAndRespectsToggles() throws Exception {
        Path pdf = write("pdf.md", "---\ndescription: Work with PDFs\n---\nUse pdftotext.");
        SkillCatalog catalog = new SkillCatalog(List.of(
                new SkillSettings("pdf", null, pdf.toString()),
                new SkillSettings("gone", "x", base.resolve("missing.md").toString())));
        SkillTool tool = new SkillTool(catalog);

        assertEquals(1, catalog.all().size(), "a skill with a missing file is skipped");
        assertTrue(tool.description().contains("pdf: Work with PDFs"));
        assertTrue(tool.execute(new ToolContext("c", base), json("{'name':'pdf'}")).contains("Use pdftotext."));

        catalog.setEnabled("pdf", false);
        assertFalse(tool.description().contains("pdf:"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(new ToolContext("c", base), json("{'name':'pdf'}")));
        assertThrows(IllegalArgumentException.class, () -> catalog.setEnabled("nope", true));
    }

    @Test
    void harnessRegistersSkillToolOnlyWithSkillsAndSkillsMenuToggles() throws Exception {
        Path pdf = write("pdf.md", "body");
        HarnessSettings settings = new HarnessSettings(null, null,
                List.of(new SkillSettings("pdf", "PDFs", pdf.toString())), null, null);
        AgentHarness h = AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base))
                .withoutStandardTools().settings(settings).build();
        String chat = h.sessions().create(null).chatId();

        assertEquals(List.of("skill"), h.tools().all().stream().map(HarnessTool::name).toList());
        String list = h.commands().execute(chat, "/skills").output();
        assertTrue(list.contains("pdf") && list.contains("[on]"), list);
        h.commands().menuInput(chat, "1");
        h.commands().menuInput(chat, "1");
        assertFalse(h.skills().isEnabled("pdf"));

        AgentHarness none = AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base))
                .withoutStandardTools().build();
        assertTrue(none.tools().all().isEmpty());
    }

    @Test
    void instructionFilesJoinInOrderAndMissingOnesAreSkipped() throws Exception {
        Path a = write("KENDAR.md", "Be brief.");
        Path b = write("EXTRA.md", "Use Java.");

        String out = InstructionLoader.load(List.of(a.toString(), base.resolve("nope.md").toString(), b.toString()));

        assertEquals("Instructions from KENDAR.md:\nBe brief.\n\nInstructions from EXTRA.md:\nUse Java.", out);
        assertEquals("", InstructionLoader.load(List.of()));
    }

    @Test
    void instructionsReachTheSystemPrompt() throws Exception {
        Path a = write("KENDAR.md", "Always say moo.");
        ScriptedModel model = new ScriptedModel().enqueue(text("ok"));
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config(base)).withoutStandardTools()
                .settings(new HarnessSettings(null, null, null, List.of(a.toString()), null)).build();
        String chat = h.sessions().create(null).chatId();

        h.start(chat, "hi");
        long until = System.currentTimeMillis() + 10_000;
        while (model.requests.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);

        assertTrue(model.requests.get(0).extra().contains("Instructions from KENDAR.md:\nAlways say moo."));
    }

    @Test
    void agentCatalogHasBuiltInsAndRejectsDuplicates() {
        AgentCatalog catalog = new AgentCatalog(List.of(new AgentDefinition("sec", "Security review", List.of(), "p")));

        assertEquals(List.of("general", "explore", "plan", "sec"),
                catalog.all().stream().map(AgentDefinition::name).toList());
        assertThrows(IllegalArgumentException.class,
                () -> new AgentCatalog(List.of(new AgentDefinition("plan", "dup", List.of(), ""))));
    }

    @Test
    void taskToolRunsTypedAgentsAndRejectsUnknownTypes() throws Exception {
        AgentLoop loop = mock(AgentLoop.class);
        AgentCatalog catalog = new AgentCatalog(List.of());
        when(loop.runSubAgent(any(), any(), eq("q"), eq(catalog.get("explore")), any(), any(), any())).thenReturn("typed");
        when(loop.runSubAgent(any(), any(), eq("q"), eq(catalog.get("general")), any(), any(), any())).thenReturn("plain");
        SubAgentTool tool = new SubAgentTool(() -> loop, MAPPER, catalog);
        ToolContext ctx = new ToolContext("c1", base);

        assertTrue(tool.execute(ctx, json("{'prompts':['q'],'subagent_type':'explore'}")).contains("typed"));
        verify(loop).runSubAgent(eq("c1"), any(), eq("q"), eq(catalog.get("explore")), any(), any(), any());
        assertTrue(tool.execute(ctx, json("{'prompts':['q']}")).contains("plain"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(ctx, json("{'prompts':['q'],'subagent_type':'x'}")));
        assertTrue(tool.description().contains("explore"));
        assertEquals(4, tool.inputSchema().path("properties").path("subagent_type").path("enum").size() + 1);
    }

    @Test
    void typedSubAgentGetsOnlyItsToolsAndPrompt() {
        Rig rig = new Rig(base);
        rig.register(tool("read_file", "Files", ToolKind.READ_ONLY, (c, i) -> ""),
                tool("other_read", "Files", ToolKind.READ_ONLY, (c, i) -> ""),
                tool("write_file", "Files", ToolKind.FILE_EDIT, (c, i) -> ""));
        rig.model.enqueue(text("found"));
        AgentDefinition scout = new AgentDefinition("scout", "Looks around", List.of("read_file"), "SCOUT RULES");

        String out = rig.loop.runSubAgent(rig.newChat(), "t1", "look", scout);

        assertEquals("found", out);
        var req = rig.model.requests.get(0);
        assertEquals(List.of("read_file"), req.toolNames());
        assertTrue(req.extra().contains("SCOUT RULES"));
    }
}
