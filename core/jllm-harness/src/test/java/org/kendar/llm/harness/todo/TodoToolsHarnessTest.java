package org.kendar.llm.harness.todo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.AgentHarnessBuilder;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.HarnessSettingsLoader;
import org.kendar.llm.harness.state.FileStateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** The todo tools offered by the harness, with any context strategy. */
class TodoToolsHarnessTest {

    private static final String WRITE = "{'todos':[{'content':'write docs','status':'in_progress'},"
            + "{'content':'run tests','status':'pending'}]}";

    @TempDir
    Path base;

    private HarnessSettings settings(String strategy, Boolean todos) throws Exception {
        Path f = Files.createTempFile(base, "settings", ".json");
        Files.writeString(f, "{\"context\":{\"strategy\":\"" + strategy + "\""
                + (todos == null ? "" : ",\"todos\":" + todos) + "}}");
        return HarnessSettingsLoader.load(f, k -> null);
    }

    private AgentHarness harness(ScriptedModel model, HarnessSettings settings, Path stateRoot,
                                 InMemoryChatSessionStore sessions) {
        return new AgentHarnessBuilder().modelClient(model).config(config(base)).withoutStandardTools()
                .settings(settings).stateStore(new FileStateStore(stateRoot)).sessionStore(sessions).build();
    }

    private static void run(AgentHarness h, String chatId, String text) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        h.subscribe(chatId, 0, ev -> {
            if (ev.type().equals("run_finished")) finished.countDown();
        });
        h.start(chatId, text);
        assertTrue(finished.await(20, TimeUnit.SECONDS));
    }

    @Test
    void unsetOrTrueOffersTheToolsWithANonScopeStrategy() throws Exception {
        for (String strategy : new String[]{"compaction", "mnemonic"}) {
          for (Boolean todos : new Boolean[]{null, true}) {
            ScriptedModel model = new ScriptedModel();
            model.enqueue(toolUse("t1", "todo_write", WRITE), toolUse("t2", "todo_read", "{}"), text("done"));
            AgentHarness h = harness(model, settings(strategy, todos), base.resolve("state-" + strategy + "-" + todos),
                    new InMemoryChatSessionStore());
            assertEquals(ContextStrategyKind.parse(strategy), h.contextStrategy().kind());
            ChatSessionData chat = h.sessions().create(null);

            run(h, chat.chatId(), "plan it");

            var tools = model.requests.get(0).toolNames();
            assertEquals(1, tools.stream().filter("todo_write"::equals).count(), strategy + ": " + tools);
            assertTrue(tools.contains("todo_read"), strategy + ": " + tools);
            assertTrue(model.requests.get(0).extra().contains("# Task list"), strategy);
            assertTrue(model.requests.get(1).messages().toString().contains("Todo list updated (2 items)"), strategy);
            assertTrue(model.requests.get(2).messages().toString().contains("[~] 1. write docs"), strategy);
          }
        }
    }

    @Test
    void falseHidesTheTools() throws Exception {
        {
            ScriptedModel model = new ScriptedModel();
            model.enqueue(toolUse("t1", "todo_write", WRITE), text("done"));
            Path state = base.resolve("state-off");
            AgentHarness h = harness(model, settings("compaction", false), state, new InMemoryChatSessionStore());
            ChatSessionData chat = h.sessions().create(null);

            run(h, chat.chatId(), "plan it");

            var tools = model.requests.get(0).toolNames();
            assertFalse(tools.contains("todo_write"), tools.toString());
            assertFalse(tools.contains("todo_read"), tools.toString());
            assertFalse(model.requests.get(0).extra().contains("# Task list"));
            assertFalse(model.requests.get(1).messages().toString().contains("Todo list updated"));
            assertTrue(new TodoStore(new FileStateStore(state), MAPPER).read(chat.chatId(), null).isEmpty(),
                    "nothing was written");
        }
    }

    @Test
    void scopeOffersTheToolsOnceWhateverTheSetting() throws Exception {
        for (Boolean todos : new Boolean[]{null, false, true}) {
            ScriptedModel model = new ScriptedModel();
            model.enqueue(text("done"));
            AgentHarness h = harness(model, settings("scope", todos), base.resolve("state-scope-" + todos),
                    new InMemoryChatSessionStore());
            ChatSessionData chat = h.sessions().create(null);

            run(h, chat.chatId(), "hello");

            var tools = model.requests.get(0).toolNames();
            assertEquals(1, tools.stream().filter("todo_write"::equals).count(), tools.toString());
            assertEquals(1, tools.stream().filter("todo_read"::equals).count(), tools.toString());
            String system = model.requests.get(0).extra();
            assertEquals(1, system.split("# Task list", -1).length - 1, "one task list section");
            assertTrue(system.contains("It is shown to you at the start of every turn"), "the scope text");
        }
    }

    @Test
    void todosSurviveAResume() throws Exception {
        Path state = base.resolve("state");
        InMemoryChatSessionStore sessions = new InMemoryChatSessionStore();
        ScriptedModel first = new ScriptedModel();
        first.enqueue(toolUse("t1", "todo_write", WRITE), text("done"));
        AgentHarness before = harness(first, settings("compaction", true), state, sessions);
        ChatSessionData chat = before.sessions().create(null);
        run(before, chat.chatId(), "plan it");

        // a new harness over the same stores: the chat is resumed
        ScriptedModel second = new ScriptedModel();
        second.enqueue(toolUse("t2", "todo_read", "{}"), text("done"));
        AgentHarness after = harness(second, settings("compaction", true), state, sessions);
        run(after, chat.chatId(), "go on");

        String result = second.requests.get(1).messages().toString();
        assertTrue(result.contains("[~] 1. write docs"), result);
        assertTrue(result.contains("[ ] 2. run tests"), result);
    }
}
