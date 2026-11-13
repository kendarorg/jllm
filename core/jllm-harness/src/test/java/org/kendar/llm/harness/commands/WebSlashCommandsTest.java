package org.kendar.llm.harness.commands;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.FileChatSessionStore;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** Slash commands typed by web users: owner and admin gating, per-chat tool toggles, no global effect. */
class WebSlashCommandsTest {

    @TempDir
    Path base;

    private AgentHarness harness;
    private SlashCommands commands;
    private static final SlashCommands.Caller ALICE = SlashCommands.Caller.web("alice", false);
    private static final SlashCommands.Caller BOB = SlashCommands.Caller.web("bob", false);
    private static final SlashCommands.Caller ADMIN = SlashCommands.Caller.web("root", true);

    @BeforeEach
    void start() {
        harness = AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base)).withoutStandardTools()
                .tools(List.of(tool("alpha", "Misc", ToolKind.READ_ONLY, (c, i) -> ""),
                        tool("beta", "Misc", ToolKind.OTHER, (c, i) -> "")))
                .sessionStore(new FileChatSessionStore(base.resolve("history"), MAPPER)).build();
        commands = harness.commands();
    }

    private String chatOf(String owner) {
        String id = harness.sessions().create("chat of " + owner, owner).chatId();
        commands.captureChatTools(id);
        return id;
    }

    @Test
    void adminOnlyCommandsAreRefusedToOtherWebUsers() {
        String a = chatOf("alice");
        assertTrue(commands.execute(a, "/model x", ALICE).output().contains("reserved to the administrator"));
        // /login and /logout are open to every user, for their own logins of per-user sources only
        assertFalse(commands.execute(a, "/login", ALICE).output().contains("reserved"));
        assertTrue(commands.execute(a, "/logout x", ALICE).output().contains("only the administrator"));
        assertFalse(commands.execute(a, "/login", ADMIN).output().contains("reserved"));
        assertTrue(commands.list(ALICE).stream().noneMatch(i -> i.name().equals("/model")));
        assertTrue(commands.list(ADMIN).stream().anyMatch(i -> i.name().equals("/model")));
    }

    @Test
    void newIsRemovedOnTheWebOnly() {
        String a = chatOf("alice");
        int before = harness.sessions().list().size();
        assertTrue(commands.execute(a, "/new", ADMIN).output().contains("not available"));
        assertTrue(commands.execute(a, "/new", ALICE).output().contains("not available"));
        assertEquals(before, harness.sessions().list().size(), "no ownerless chat was created");
        assertTrue(commands.list(ADMIN).stream().noneMatch(i -> i.name().equals("/new")));
        var r = commands.execute(a, "/new"); // command line
        assertNotNull(r.switchToChatId());
        assertEquals(before + 1, harness.sessions().list().size());
    }

    @Test
    void chatsAndResumeSeeOnlyOwnChats() {
        String a = chatOf("alice");
        String b = chatOf("bob");
        String list = commands.execute(a, "/chats", ALICE).output();
        assertTrue(list.contains(a));
        assertFalse(list.contains(b), list);
        assertTrue(commands.execute(a, "/resume " + b, ALICE).output().startsWith("error: Unknown chat"));
        assertTrue(commands.execute(a, "/chats delete " + b, ALICE).output().startsWith("error: Unknown chat"));
        assertTrue(harness.sessions().find(b).isPresent(), "bob's chat survives");
        String all = commands.execute(a, "/chats", ADMIN).output();
        assertTrue(all.contains(a) && all.contains(b));
        assertEquals(b, commands.execute(a, "/resume " + b, ADMIN).switchToChatId());
        assertEquals(a, commands.execute(b, "/resume " + a.substring(0, a.length() - 2), ADMIN).switchToChatId());
        assertTrue(commands.execute(b, "/chats delete " + a, BOB).output().startsWith("error: Unknown chat"));
    }

    @Test
    void toolTogglesChangeOnlyTheChat() {
        String a = chatOf("alice");
        String b = chatOf("bob");
        String out = commands.execute(a, "/tools disable beta", ALICE).output();
        assertTrue(out.contains("Disabled for this chat: beta"), out);
        assertFalse(harness.sessions().chatAllowsTool(a, "beta"));
        assertTrue(harness.sessions().chatAllowsTool(a, "alpha"));
        assertTrue(harness.sessions().chatAllowsTool(b, "beta"), "other chats keep it");
        assertTrue(harness.tools().isEnabled("beta"), "no global effect");
        commands.execute(a, "/tools enable Misc", ALICE);
        assertTrue(harness.sessions().chatAllowsTool(a, "beta"));
        // the command line still toggles globally
        commands.execute(a, "/tools disable beta");
        assertFalse(harness.tools().isEnabled("beta"));
    }

    @Test
    void cannotEnableBeyondTheSetOfTheChatStart() {
        harness.tools().disable(List.of("beta"));
        String a = chatOf("alice");
        harness.tools().enable(List.of("beta"));
        String out = commands.execute(a, "/tools enable beta", ALICE).output();
        assertTrue(out.contains("not available in this chat"), out);
        assertTrue(commands.execute(a, "/skills enable nope", ALICE).output().contains("not available"));
        assertTrue(commands.execute(a, "/mcp enable nope", ALICE).output().contains("not available"));
    }

    @Test
    void perChatMenuTogglesTheChatSet() {
        String a = chatOf("alice");
        String menu = commands.execute(a, "/tools", ALICE).output();
        assertTrue(menu.contains("of this chat"), menu);
        String line = menu.lines().filter(l -> l.contains(" beta ")).findFirst().orElseThrow();
        commands.menuInput(a, line.strip().split("\\.")[0], ALICE); // then action 1 = toggle
        String after = commands.menuInput(a, "1", ALICE).output();
        assertTrue(after.contains("for this chat: beta"), after);
        assertFalse(harness.sessions().chatAllowsTool(a, "beta"));
        assertTrue(harness.tools().isEnabled("beta"));
    }

    @Test
    void menuInputNeverThrows() {
        String a = chatOf("alice");
        commands.execute(a, "/mode", ALICE);
        harness.sessions().delete(a); // the chat vanished while its menu was open
        var r = assertDoesNotThrow(() -> commands.menuInput(a, "1", ALICE));
        assertTrue(r.output().startsWith("error:"), r.output());
    }

    @Test
    void chatToolSetSurvivesARestart() {
        String a = chatOf("alice");
        commands.execute(a, "/tools disable alpha", ALICE);
        FileChatSessionStore reopened = new FileChatSessionStore(base.resolve("history"), MAPPER);
        ChatSessionData row = reopened.find(a).orElseThrow();
        assertNotNull(row.chatTools());
        assertFalse(org.kendar.llm.harness.session.ChatToolSet.parse(row.chatTools()).allowsTool("alpha"));
    }
}
