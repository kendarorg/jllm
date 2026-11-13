package org.kendar.llm.harness.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.SkillSettings;
import org.kendar.llm.harness.tools.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** Skills a user policy brings on top of the harness ones, and harness skills a policy hides. */
class UserSkillsTest {

    @TempDir
    Path base;

    /** Per user: own skills and the harness skills kept; changed between turns by the tests. */
    private final Map<String, List<SkillSettings>> own = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> hidden = new ConcurrentHashMap<>();

    private Path write(String name, String content) throws Exception {
        return Files.writeString(base.resolve(name), content);
    }

    private AgentHarness harness(ScriptedModel model, List<SkillSettings> harnessSkills) {
        HarnessSettings settings = new HarnessSettings(null, null, harnessSkills, null, null);
        return AgentHarness.builder().modelClient(model).config(config(base)).withoutStandardTools()
                .settings(settings)
                .userPolicyResolver(user -> user == null ? UserPolicy.ALL
                        : new UserPolicy(null, null, own.getOrDefault(user.id(), List.of()),
                        name -> !hidden.getOrDefault(user.id(), Set.of()).contains(name)))
                .principalDirectory(id -> new HarnessPrincipal(id, Set.of("user"), Map.of()))
                .build();
    }

    @Test
    void ownSkillIsOfferedAndLoadedOnlyForItsUser() throws Exception {
        Path notes = write("notes.md", "---\ndescription: My notes\n---\nAlways write notes.");
        own.put("alice", List.of(new SkillSettings("notes", null, notes.toString())));
        ScriptedModel model = new ScriptedModel()
                .enqueue(toolUse("t1", "skill", "{\"name\":\"notes\"}"), text("ok"))
                .enqueue(text("ok"));
        AgentHarness h = harness(model, List.of());

        List<AgentEvent> alice = run(h, h.sessions().create("a", "alice").chatId());
        run(h, h.sessions().create("b", "bob").chatId());

        assertTrue(model.requests.get(0).toolNames().contains("skill"), "alice gets the skill tool");
        String out = alice.stream().filter(e -> e.type().equals("tool_result")).findFirst().orElseThrow()
                .data().path("output").asText();
        assertTrue(out.contains("Always write notes."), out);
        // bob has no skill at all: no skill tool (the harness has none either)
        assertFalse(model.requests.get(2).toolNames().contains("skill"), model.requests.get(2).toolNames().toString());
    }

    @Test
    void policyHidesHarnessSkillAndOwnSkillCannotShadowIt() throws Exception {
        Path pdf = write("pdf.md", "harness pdf");
        Path fake = write("fake.md", "user pdf");
        Path csv = write("csv.md", "harness csv");
        SkillCatalog catalog = new SkillCatalog(List.of(new SkillSettings("pdf", "PDF", pdf.toString()),
                new SkillSettings("csv", "CSV", csv.toString())));
        SkillTool view = new SkillTool(catalog).forChat(name -> !name.equals("csv"),
                List.of(new SkillSettings("pdf", "mine", fake.toString()),
                        new SkillSettings("gone", "x", base.resolve("missing.md").toString())));

        assertEquals(List.of("pdf"), view.offered().stream().map(SkillCatalog.Skill::name).toList());
        assertEquals("harness pdf", view.execute(new ToolContext("c", base), json("{'name':'pdf'}")));
        assertThrows(IllegalArgumentException.class,
                () -> view.execute(new ToolContext("c", base), json("{'name':'csv'}")));
    }

    @Test
    void policyChangeBetweenTurnsChangesTheOfferedSkills() throws Exception {
        Path pdf = write("pdf.md", "harness pdf");
        ScriptedModel model = new ScriptedModel().enqueue(text("one")).enqueue(text("two"));
        AgentHarness h = harness(model, List.of(new SkillSettings("pdf", "PDF", pdf.toString())));
        String chat = h.sessions().create("a", "alice").chatId();

        run(h, chat);
        hidden.put("alice", Set.of("pdf"));
        run(h, chat);

        assertTrue(model.requests.get(0).toolNames().contains("skill"));
        assertFalse(model.requests.get(1).toolNames().contains("skill"), "the only skill is hidden: no tool");
    }

    private List<AgentEvent> run(AgentHarness h, String chat) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        Runnable stop = h.subscribe(chat, h.lastSeq(chat), ev -> {
            events.add(ev);
            if (ev.type().equals("run_finished")) done.countDown();
        });
        h.start(chat, "go");
        assertTrue(done.await(20, TimeUnit.SECONDS));
        stop.run();
        return events;
    }
}
