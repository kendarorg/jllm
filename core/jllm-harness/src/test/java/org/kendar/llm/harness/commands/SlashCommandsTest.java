package org.kendar.llm.harness.commands;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.AgentHarnessBuilder;
import org.kendar.llm.harness.session.FileChatSessionStore;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.HarnessSettingsLoader;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** The less used slash commands, their usage messages and their error paths, for the local user. */
class SlashCommandsTest {

    @TempDir
    Path base;

    private final ScriptedModel model = new ScriptedModel();
    private final CountDownLatch release = new CountDownLatch(1);
    private AgentHarness harness;
    private SlashCommands commands;
    private String chat;

    @BeforeEach
    void start() throws Exception {
        model.handler = r -> {
            String last = r.messages().get(r.messages().size() - 1).toString();
            if (last.contains("BLOCK")) {
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return text("done");
        };
        model.simpleHandler = u -> "summary";
        harness = build(HarnessSettings.empty());
        commands = harness.commands();
        chat = harness.sessions().create("first").chatId();
    }

    @AfterEach
    void stop() {
        release.countDown();
    }

    private AgentHarness build(HarnessSettings settings) {
        return new AgentHarnessBuilder().modelClient(model).config(config(base)).withoutStandardTools()
                .tools(List.of(tool("alpha", "Misc", ToolKind.READ_ONLY, (c, i) -> ""),
                        tool("beta", "Other", ToolKind.OTHER, (c, i) -> "")))
                .sessionStore(new FileChatSessionStore(base.resolve("history"), MAPPER))
                .settings(settings).build();
    }

    private HarnessSettings settings(String json) throws Exception {
        return HarnessSettingsLoader.load(Files.writeString(base.resolve("settings.json"), json));
    }

    private String run(String line) {
        assertTrue(commands.handles(line), line);
        return commands.execute(chat, line).output();
    }

    private void waitIdle() throws InterruptedException {
        for (int i = 0; i < 500 && harness.isRunning(chat); i++) Thread.sleep(10);
        assertFalse(harness.isRunning(chat));
    }

    private void addMessages(String... texts) {
        ArrayNode messages = harness.sessions().messages(harness.sessions().get(chat));
        boolean user = true;
        for (String t : texts) {
            messages.addObject().put("role", user ? "user" : "assistant").put("content", t);
            user = !user;
        }
        harness.sessions().saveMessages(chat, messages);
    }

    private int messageCount() {
        return harness.sessions().messages(harness.sessions().get(chat)).size();
    }

    // ---------------------------------------------------------------- recognition and registration

    @Test
    void handlesOnlyKnownCommandNames() {
        assertTrue(commands.handles("/help"));
        assertTrue(commands.handles("/title  some text"));
        assertFalse(commands.handles(null));
        assertFalse(commands.handles("help"));
        assertFalse(commands.handles("/helpme"));
        assertEquals("unknown command /helpme", commands.execute(chat, "/helpme").output());
    }

    @Test
    void helpListsEveryCommandWithItsDescription() {
        String help = run("/help");
        for (SlashCommands.Info i : commands.list()) {
            assertTrue(help.contains(i.name() + "  " + i.description()), i.name());
        }
        assertEquals("", run("/exit"), "the command line quits before; elsewhere a no-op");
    }

    @Test
    void customCommandsGetTheArgumentAndTheTextOfTheChat() {
        addMessages("first question", "first answer");
        List<String> seen = new ArrayList<>();
        commands.register("/echo", "Echo the argument", (chatId, arg, history) -> {
            history.forEach(l -> seen.add(l.role() + ":" + l.text()));
            return chatId + " said " + arg;
        });

        assertEquals(chat + " said hello world", run("/echo hello world"));
        assertEquals(List.of("user:first question", "assistant:first answer"), seen);
        assertTrue(commands.list().stream().anyMatch(i -> i.name().equals("/echo")));
        assertTrue(run("/help").contains("/echo  Echo the argument"));
    }

    @Test
    void invalidOrTakenCommandNamesAreRefused() {
        for (String bad : new String[]{"echo", "/", "/two words"}) {
            var e = assertThrows(IllegalArgumentException.class, () -> commands.register(bad, "d", (c, a, h) -> ""));
            assertTrue(e.getMessage().contains("Invalid command name"), e.getMessage());
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> commands.register("/help", "d", (c, a, h) -> "")).getMessage().contains("Duplicate command /help"));
        commands.register("/mine", "d", (c, a, h) -> "");
        assertThrows(IllegalArgumentException.class, () -> commands.register("/mine", "d", (c, a, h) -> ""));
    }

    @Test
    void aFailingCustomCommandIsReportedAsOutput() {
        commands.register("/boom", "fails", (c, a, h) -> {
            throw new IllegalStateException("kaput");
        });
        assertEquals("error: kaput", run("/boom"));
    }

    // ---------------------------------------------------------------- conversation

    @Test
    void clearEmptiesTheConversation() {
        addMessages("q", "a");
        var r = commands.execute(chat, "/clear");
        assertEquals("Conversation cleared", r.output());
        assertTrue(r.refresh());
        assertEquals(0, messageCount());
    }

    @Test
    void newChatKeepsTheModeAndSwitches() {
        run("/plan");
        var r = commands.execute(chat, "/new");
        assertNotNull(r.switchToChatId());
        assertNotEquals(chat, r.switchToChatId());
        assertEquals("chat " + r.switchToChatId(), r.output());
        assertEquals("PLAN", harness.sessions().get(r.switchToChatId()).mode());
    }

    @Test
    void titleRenamesTheChat() {
        assertEquals("usage: /title <text>", run("/title"));
        var r = commands.execute(chat, "/title  Better name ");
        assertEquals("Title: Better name", r.output());
        assertTrue(r.refresh());
        assertEquals("Better name", harness.sessions().get(chat).title());
    }

    @Test
    void resumeNeedsAnId() {
        assertEquals("usage: /resume <id or unique prefix>", run("/resume"));
        assertTrue(run("/resume no-such-chat").startsWith("error: "));
    }

    @Test
    void compactWithNothingToSummarizeSaysSo() {
        var r = commands.execute(chat, "/compact");
        assertTrue(r.refresh());
        assertFalse(r.output().isBlank());
        assertFalse(r.output().startsWith("Compacted"), r.output());
    }

    @Test
    void undoWithoutChangesHasNothingToUndo() {
        assertEquals("Nothing to undo", run("/undo"));
        assertEquals("Nothing to undo", run("/undo 3"));
        assertEquals("Nothing to undo", run("/undo many"), "a non-numeric count means one turn");
    }

    // ---------------------------------------------------------------- modes

    @Test
    void modeShortcutsAndModeWithANameSetTheMode() {
        assertEquals("Mode: PLAN", run("/plan"));
        assertEquals("PLAN", harness.sessions().get(chat).mode());
        assertEquals("Mode: EDIT", run("/edit"));
        assertEquals("Mode: AUTO", run("/auto"));
        assertEquals("Mode: DEFAULT", run("/default"));
        assertEquals("Mode: EDIT", run("/mode edit"));
        assertEquals("EDIT", harness.sessions().get(chat).mode());
    }

    @Test
    void modeMenuMarksTheCurrentModeAndRejectsBadNumbers() {
        run("/edit");
        String menu = run("/mode");
        assertTrue(menu.contains("2. edit  [current]"), menu);
        assertTrue(commands.menuActive(chat));

        var bad = commands.menuInput(chat, "7");
        assertTrue(bad.output().startsWith("No mode 7"), bad.output());
        assertTrue(bad.menu());
        assertTrue(commands.menuActive(chat), "a bad number keeps the menu open");

        var ok = commands.menuInput(chat, "3");
        assertEquals("Mode: AUTO", ok.output());
        assertFalse(commands.menuActive(chat));
        assertEquals("AUTO", harness.sessions().get(chat).mode());
    }

    @Test
    void menuInputAndBackWithoutAMenu() {
        assertEquals("No menu is open", commands.menuInput(chat, "1").output());
        assertEquals("No menu is open", run("/back"));
        assertTrue(commands.menuInput(chat, null).output().contains("Type a number"));
    }

    // ---------------------------------------------------------------- runs

    @Test
    void steerAndFollowupNeedText() {
        assertEquals("usage: /steer <text>", run("/steer"));
        assertEquals("usage: /followup <text>", run("/followup   "));
    }

    @Test
    void followupWhileIdleStartsARun() throws Exception {
        var r = commands.execute(chat, "/followup please continue");
        assertEquals("", r.output());
        assertTrue(r.refresh());
        waitIdle();
        assertTrue(model.requests.stream().anyMatch(q -> q.messages().toString().contains("please continue")));
    }

    @Test
    void commandsThatNeedAnIdleChatRefuseDuringARun() throws Exception {
        addMessages("keep", "me");
        harness.start(chat, "BLOCK");
        for (int i = 0; i < 500 && model.requests.isEmpty(); i++) Thread.sleep(10);
        assertTrue(harness.isRunning(chat));

        assertEquals("A run is in progress", run("/clear"));
        assertEquals("A run is in progress", run("/undo"));
        assertEquals("A run is in progress", run("/model openai"));
        assertTrue(messageCount() >= 2, "nothing was cleared");

        assertEquals("", run("/steer also this"));
        assertEquals("Cancel requested", run("/cancel"));
        release.countDown();
        waitIdle();
    }

    // ---------------------------------------------------------------- settings and state

    @Test
    void validationIsShownAndSetPerChat() {
        assertEquals("Tool argument validation: lenient", run("/validation"), "the default");
        assertEquals("Tool argument validation: strict", run("/validation strict"));
        assertEquals("Tool argument validation: strict", run("/validation"));
        String other = harness.sessions().create(null).chatId();
        assertEquals("Tool argument validation: lenient", commands.execute(other, "/validation").output());
        assertTrue(run("/validation sometimes").startsWith("error: "));
    }

    @Test
    void permissionsListTheRulesOfTheSettings() throws Exception {
        assertEquals("No permission rules", run("/permissions"));

        harness = build(settings("{\"permissions\": {\"deny\": [\"bash(rm)\"], \"ask\": [\"write_file\"]}}"));
        commands = harness.commands();
        chat = harness.sessions().create(null).chatId();
        String rules = run("/permissions");
        assertTrue(rules.contains("deny  bash(rm)"), rules);
        assertTrue(rules.contains("ask  write_file"), rules);
    }

    @Test
    void costAndStatusDescribeTheChat() {
        String cost = run("/cost");
        assertTrue(cost.startsWith("input 0, output 0, cache read 0, cache write 0, cost $"), cost);

        run("/plan");
        String status = run("/status");
        assertTrue(status.startsWith("chat: " + chat + " \"first\""), status);
        assertTrue(status.contains("mode: PLAN"), status);
        assertTrue(status.contains("running agents: none"), status);
        assertTrue(status.contains("context strategy: "), status);
        assertTrue(status.contains("chats: 1"), status);
    }

    @Test
    void sandboxSubcommandsOnALocalSandbox() {
        assertEquals("usage: /sandbox clean | /sandbox backup", run("/sandbox"));
        assertEquals("usage: /sandbox clean | /sandbox backup", run("/sandbox wipe"));
        assertEquals("This sandbox needs no backup (only remote docker does)", run("/sandbox backup"));
        assertEquals("This sandbox has no container to clean", run("/sandbox clean"));
    }

    // ---------------------------------------------------------------- export

    @Test
    void exportWritesMarkdownIntoTheScratchDirectoryByDefault() throws Exception {
        addMessages("what is two plus two", "four");
        String out = run("/export");
        assertTrue(out.startsWith("exported 2 messages to "), out);
        Path file = harness.scratchDir(chat).resolve("chat-" + chat + ".md");
        String md = Files.readString(file);
        assertTrue(md.contains("what is two plus two") && md.contains("four"), md);
    }

    @Test
    void exportAsHtmlToANamedFile() throws Exception {
        addMessages("hello <b>", "hi");
        run("/export html sub/t.html");
        String html = Files.readString(harness.scratchDir(chat).resolve("sub").resolve("t.html"));
        assertTrue(html.contains("&lt;b&gt;"), "message text is escaped:\n" + html);

        run("/export notes.md");
        assertTrue(Files.exists(harness.scratchDir(chat).resolve("notes.md")), "a bare name is a markdown file");
    }

    @Test
    void exportOutsideTheScratchDirectoryIsRefused() {
        assertEquals("error: the file must be inside the scratch directory", run("/export md ../outside.md"));
        assertFalse(Files.exists(harness.scratchDir(chat).getParent().resolve("outside.md")));
    }

    // ---------------------------------------------------------------- chats

    @Test
    void chatsSubcommandsCheckTheirArguments() {
        assertEquals("usage: /chats | /chats delete <id> | /chats clean [days]", run("/chats purge"));
        assertEquals("usage: /chats delete <id or unique prefix>", run("/chats delete"));
        assertEquals("usage: /chats clean [days]", run("/chats clean soon"));
        assertEquals("Nothing to confirm: run /chats clean [days] first", run("/chats clean confirm"));
        assertEquals("No chat older than 30 days", run("/chats clean"));
        assertEquals("No chat older than 5 days", run("/chats clean 5"));
    }

    // ---------------------------------------------------------------- logins

    @Test
    void loginAndLogoutWithoutLoginSources() {
        assertEquals("No logins available", run("/login"));
        assertEquals("unknown login nope", run("/login nope"));
        assertEquals("usage: /logout <name> [shared]", run("/logout"));
        assertEquals("No stored login for nope", run("/logout nope"));
    }

    // ---------------------------------------------------------------- menus: tools, skills, sources

    @Test
    void toolsScriptedFormsAndUsage() {
        assertEquals("usage: /tools [number | enable|disable|check <name>]", run("/tools check"));
        assertEquals("usage: /tools [number | enable|disable|check <name>]", run("/tools alpha"));
        assertEquals("usage: /tools enable|disable <tool|group>", run("/tools toggle alpha"));
        assertEquals("No tool or group 'gamma'", run("/tools disable gamma"));
        assertEquals("Disabled: alpha", run("/tools disable Misc"));
        assertFalse(harness.tools().isEnabled("alpha"));
        assertEquals("Enabled: alpha", run("/tools enable alpha"));
        assertTrue(harness.tools().isEnabled("alpha"));
    }

    @Test
    void aNumberOpensTheMenuAndSelects() {
        String selected = run("/tools 1");
        assertTrue(commands.menuActive(chat));
        assertTrue(selected.startsWith("Misc  [1/1 on]"), selected);
        assertTrue(selected.contains("1. Disable all"), selected);
        var bad = commands.menuInput(chat, "5");
        assertTrue(bad.output().startsWith("No action 5"), bad.output());
        String done = commands.menuInput(chat, "1").output();
        assertTrue(done.startsWith("Disable all: Misc"), done);
        assertFalse(harness.tools().isEnabled("alpha"));
        assertTrue(harness.tools().isEnabled("beta"), "only the group's tools");

        assertTrue(commands.menuInput(chat, "b").output().contains("Menu closed"));
    }

    @Test
    void skillsMenuAndScriptedFormsWithoutSkills() {
        String menu = run("/skills");
        assertTrue(menu.contains("Skills  (number + Enter"), menu);
        assertTrue(menu.contains("(none)"), menu);
        assertTrue(commands.menuInput(chat, "1").output().startsWith("No item 1"));
        assertEquals("Menu closed", commands.menuInput(chat, "/back").output());

        assertEquals("usage: /skills enable|disable <name>", run("/skills toggle x"));
        assertEquals("Unknown skill 'x'", run("/skills enable x"));
    }

    @Test
    void sourcesScriptedFormsWithoutSources() {
        assertEquals("(none)", run("/mcp check"));
        assertEquals("(none)", run("/apis check"));
        assertEquals("usage: enable|disable|check <name>", run("/mcp restart srv"));
        assertEquals("usage: /mcp [number | enable|disable|check <name>]", run("/mcp srv"));
        String menu = run("/apis");
        assertTrue(menu.startsWith("REST APIs  (number + Enter"), menu);
        assertTrue(menu.contains("(none)"), menu);
    }

    // ---------------------------------------------------------------- model

    private static final String PROVIDERS = """
            {"providers": [
              {"name": "big", "type": "openai", "baseUrl": "http://127.0.0.1:1/v1", "apiKey": "k", "model": "big-m",
               "contextTokens": 100000},
              {"name": "tiny", "type": "openai", "baseUrl": "http://127.0.0.1:1/v1", "apiKey": "k", "model": "tiny-m",
               "contextTokens": 10}]}
            """;

    private void withProviders() throws Exception {
        harness = build(settings(PROVIDERS));
        commands = harness.commands();
        chat = harness.sessions().create(null).chatId();
    }

    @Test
    void modelByNameSwitchesOrRefusesAnUnknownProvider() throws Exception {
        withProviders();
        assertEquals("Unknown provider nope", run("/model nope"));
        assertEquals("Model: big (big-m)", run("/model big"));
        assertEquals("big", harness.currentProvider());
    }

    @Test
    void modelMenuListsProvidersAndSwitches() throws Exception {
        withProviders();
        String menu = run("/model");
        assertTrue(menu.contains("1. big  [big-m, 100000 ctx]"), menu);
        assertTrue(menu.contains("2. tiny  [tiny-m, 10 ctx]"), menu);
        assertTrue(commands.menuInput(chat, "9").output().startsWith("No model 9"));

        var r = commands.menuInput(chat, "1");
        assertEquals("Model: big (big-m)", r.output());
        assertTrue(r.refresh());
        assertFalse(commands.menuActive(chat));
        assertTrue(run("/model").contains("1. big  [big-m, 100000 ctx]  [current]"));
    }

    @Test
    void aModelTooSmallForTheConversationAsksToCompactFirst() throws Exception {
        withProviders();
        addMessages("a conversation long enough not to fit in ten tokens of context, surely",
                "indeed it is far too long for such a small window");
        run("/model");
        String ask = commands.menuInput(chat, "2").output();
        assertTrue(ask.contains("does not fit tiny (10 tokens). Compact first?"), ask);
        assertTrue(ask.contains("Compact the conversation, then switch to tiny?"), ask);

        String back = commands.menuInput(chat, "b").output();
        assertTrue(back.startsWith("Model  (number + Enter"), "back leaves the question for the list: " + back);

        commands.menuInput(chat, "2");
        String no = commands.menuInput(chat, "2").output();
        assertTrue(no.startsWith("Model unchanged"), no);
        assertNull(harness.currentProvider());

        commands.menuInput(chat, "2");
        String yes = commands.menuInput(chat, "1").output();
        assertTrue(yes.contains("model unchanged") || yes.startsWith("error: "), yes);
        assertNotEquals("tiny", harness.currentProvider());
        assertTrue(commands.menuActive(chat), "a refused switch keeps the menu");
    }
}
