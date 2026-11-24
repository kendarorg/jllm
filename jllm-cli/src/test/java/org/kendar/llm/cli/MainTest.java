package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The whole command line session against a fake OpenAI compatible server: flags, settings, REPL and runs. */
class MainTest {

    @TempDir
    Path tmp;

    private Path project;
    private Path home;
    private FakeOpenAiServer server;
    private CliSession cli;

    @BeforeEach
    void start() throws Exception {
        project = Files.createDirectories(tmp.resolve("project"));
        home = Files.createDirectories(tmp.resolve("home"));
        server = new FakeOpenAiServer();
    }

    @AfterEach
    void stop() {
        if (cli != null) cli.close();
        server.close();
    }

    /** Flags for the fake server as a session-only provider, plus {@code more}. */
    private String[] args(String... more) {
        List<String> a = new ArrayList<>(List.of("--workdir", project.toString(), "--provider", "fake",
                "--base-url", server.baseUrl(), "--model", "fake-model", "--api-key", "k1"));
        a.addAll(List.of(more));
        return a.toArray(String[]::new);
    }

    private CliSession cli() {
        cli = new CliSession(home);
        return cli;
    }

    private static String lastUserText(JsonNode request) {
        JsonNode messages = request.path("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode m = messages.get(i);
            if (m.path("role").asText().equals("user")) return m.path("content").toString();
        }
        return "";
    }

    private static String systemText(JsonNode request) {
        for (JsonNode m : request.path("messages")) {
            if (m.path("role").asText().equals("system")) return m.path("content").toString();
        }
        return "";
    }

    private static String chatIdIn(String output) {
        Matcher m = Pattern.compile("agent-harness chat (\\S+)").matcher(output);
        assertTrue(m.find(), output);
        return m.group(1);
    }

    // ---------------------------------------------------------------- arguments

    @Test
    void helpPrintsTheUsageAndStartsNothing() throws Throwable {
        String out = cli().start("--help").end();
        assertTrue(out.contains(CliArgs.USAGE), out);
        assertTrue(server.requests.isEmpty());
        assertFalse(Files.exists(project.resolve(".jllm")), "no project state is created for --help");
    }

    @Test
    void aStrayArgumentIsRejected() throws Exception {
        CliSession s = cli().start("stray");
        Throwable t = s.failure();
        assertInstanceOf(IllegalArgumentException.class, t);
        assertTrue(t.getMessage().contains("Unexpected argument stray"), t.getMessage());
    }

    // ---------------------------------------------------------------- one prompt

    @Test
    void promptRunsOneTurnWithTheSessionProviderAndReturns() throws Throwable {
        CliSession s = cli().start(args("--prompt", "hi there", "--max-tokens", "1234",
                "--system-prompt", "SYS-MARK-1"));
        s.await("project dir: ");
        s.await(project.getFileName().toString());
        s.await("no isolation");
        s.await("echo: hi there");
        String out = s.end();

        assertFalse(out.contains("agent-harness chat"), "no REPL in --prompt mode:\n" + out);
        JsonNode req = server.agentRequests().get(0);
        assertEquals("fake-model", req.path("model").asText());
        assertEquals(1234, req.path(req.has("max_tokens") ? "max_tokens" : "max_completion_tokens").asInt());
        assertTrue(systemText(req).contains("SYS-MARK-1"), systemText(req));
        assertTrue(lastUserText(req).contains("hi there"));
        assertEquals("Bearer k1", server.auth.get(0));
        assertTrue(Files.isDirectory(project.resolve(".jllm").resolve("history")), "chats are kept in the project");
    }

    @Test
    void askTokenReadsTheKeyFromStdinBeforeTheChat() throws Throwable {
        List<String> a = new ArrayList<>(List.of(args("--prompt", "keyed")));
        a.set(a.indexOf("k1"), Main.ASK_TOKEN);
        CliSession s = cli().type("  typed-secret  ").start(a.toArray(String[]::new));
        s.await("API key: ");
        s.await("echo: keyed");
        s.end();
        assertEquals("Bearer typed-secret", server.auth.get(0));
    }

    @Test
    void anEmptyAskedTokenStopsTheStart() throws Exception {
        List<String> a = new ArrayList<>(List.of(args("--prompt", "x")));
        a.set(a.indexOf("k1"), Main.ASK_TOKEN);
        CliSession s = cli().type("").start(a.toArray(String[]::new));
        Throwable t = s.failure();
        assertInstanceOf(IllegalArgumentException.class, t);
        assertEquals("No API key given", t.getMessage());
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void systemPromptFileIsAddedToTheSystemPrompt() throws Throwable {
        Path file = Files.writeString(tmp.resolve("extra.md"), "FROM-THE-FILE-7");
        CliSession s = cli().start(args("--prompt", "p", "--system-prompt-file", file.toString()));
        s.await("echo: p");
        s.end();
        assertTrue(systemText(server.agentRequests().get(0)).contains("FROM-THE-FILE-7"));
    }

    // ---------------------------------------------------------------- settings

    @Test
    void globalAndProjectSettingsAreLayeredAndPickTheProvider() throws Throwable {
        Path global = Files.createDirectories(home.resolve(".jllm")).resolve("settings.json");
        Files.writeString(global, """
                {"providers": [{"name": "local", "type": "openai", "baseUrl": "%s", "apiKey": "global-key",
                                "model": "global-model", "stream": false}]}
                """.formatted(server.baseUrl()));
        Path projectSettings = Files.createDirectories(project.resolve(".jllm")).resolve("settings.json");
        Files.writeString(projectSettings, "{\"defaultProvider\": \"local\"}");

        CliSession s = cli().start("--workdir", project.toString(), "--prompt", "layered");
        s.await("echo: layered");
        s.end();

        JsonNode req = server.agentRequests().get(0);
        assertEquals("global-model", req.path("model").asText());
        assertFalse(req.path("stream").asBoolean(false), "the provider of the settings does not stream");
        assertEquals("Bearer global-key", server.auth.get(0));
    }

    @Test
    void settingsFlagWinsAndModelFlagOverridesTheProviderForThisSession() throws Throwable {
        Path settings = Files.writeString(tmp.resolve("explicit.json"), """
                {"providers": [{"name": "local", "type": "openai", "baseUrl": "%s", "apiKey": "file-key",
                                "model": "file-model"}], "defaultProvider": "local"}
                """.formatted(server.baseUrl()));
        CliSession s = cli().start("--workdir", project.toString(), "--settings", settings.toString(),
                "--model", "flag-model", "--prompt", "override");
        s.await("echo: override");
        s.end();

        assertEquals("flag-model", server.agentRequests().get(0).path("model").asText());
        assertEquals("Bearer file-key", server.auth.get(0), "the rest of the entry is kept");
        assertTrue(Files.readString(settings).contains("file-model"), "the session entry is never saved");
    }

    @Test
    void untrustedProjectMcpServerIsAskedAboutAndDroppedOnNo() throws Throwable {
        Path projectSettings = Files.createDirectories(project.resolve(".jllm")).resolve("settings.json");
        Files.writeString(projectSettings, """
                {"mcpServers": [{"name": "local-tool", "command": "jllm-no-such-command", "args": ["--x"]}]}
                """);
        CliSession s = cli().start(args());
        s.await("define the MCP server 'local-tool', which runs the local command:");
        s.await("jllm-no-such-command --x");
        s.await("Run it? [y/N] ");
        s.type("n");
        s.await("agent-harness chat ");
        String out = s.end();
        assertFalse(out.contains("[error]"), out);
        assertTrue(Files.readString(home.resolve(".jllm").resolve("trusted-mcp.json")).contains("local-tool"),
                "the answer is remembered");
    }

    // ---------------------------------------------------------------- REPL

    @Test
    void replRunsCommandsAndPrompts() throws Throwable {
        CliSession s = cli().start(args());
        s.await("agent-harness chat ");
        s.await("default> ");

        s.type("/help");
        s.await("/clear  Clear this conversation");
        s.await("default> ");

        s.type("/mode plan");
        s.await("Mode: PLAN");
        s.await("plan> ");

        s.type("/mode");
        s.await("Mode  (number + Enter to select, Esc to close)");
        s.type("2");
        s.await("Mode: EDIT");
        s.await("edit> ");

        s.type("   ");
        s.type("/expand");
        s.await("No tool call to expand.");

        s.type("/followup queued while idle");
        s.await("echo: queued while idle");

        s.type("plain prompt");
        s.await("echo: plain prompt");

        s.type("/exit");
        s.await("Stop the whole process and exit? [y/N] ");
        s.type("n");
        s.await("edit> ");
        s.type("/quit");
        s.await("Stop the whole process and exit? [y/N] ");
        s.type("y");
        assertNull(s.failure(), "the session ended normally");
        assertEquals(2, server.agentRequests().size());
    }

    @Test
    void newChatSwitchesTheSession() throws Throwable {
        CliSession s = cli().start(args());
        String first = chatIdIn(s.await("agent-harness chat "));
        s.await("default> ");
        s.type("/new");
        Matcher m = Pattern.compile("> chat (\\S+)").matcher(s.await("default> "));
        assertTrue(m.find());
        String second = m.group(1);
        assertNotEquals(first, second);
        s.type("in the new chat");
        s.await("echo: in the new chat");
        s.await("default> ");
        s.type("/chats");
        String list = s.await("* " + second);
        assertTrue(list.contains("  " + first), list);
        s.end();
    }

    @Test
    void approvedWriteCreatesTheFileAndTheResultIsShown() throws Throwable {
        CliSession s = cli().start(args());
        s.await("default> ");
        s.type("PLEASE WRITE the file");
        s.await("[tool] write_file {\"path\":\"out.txt\"");
        s.await("[approval] tool write_file");
        s.await("+ " + FakeOpenAiServer.WRITTEN);
        s.await("Allow? [y/N/a=allow for this chat");
        s.type("y");
        s.await("[result]");
        s.await("Wrote 20 chars to out.txt");
        s.await("tool said:");
        s.await("default> ");
        assertEquals(FakeOpenAiServer.WRITTEN, Files.readString(project.resolve("out.txt")));

        s.type("/expand");
        s.await("No tool call to expand."); // plain mode keeps no collapsed calls
        s.end();
    }

    @Test
    void rejectedWriteLeavesNoFile() throws Throwable {
        CliSession s = cli().start(args());
        s.await("default> ");
        s.type("PLEASE WRITE the file");
        s.await("Allow? [y/N");
        s.type("n");
        s.await("tool said:");
        s.await("default> ");
        s.end();
        assertFalse(Files.exists(project.resolve("out.txt")));
    }

    @Test
    void autoModeFlagWritesWithoutAsking() throws Throwable {
        CliSession s = cli().start(args("--mode", "auto", "--prompt", "PLEASE WRITE now"));
        s.await("tool said:");
        String out = s.end();
        assertFalse(out.contains("[approval]"), out);
        assertEquals(FakeOpenAiServer.WRITTEN, Files.readString(project.resolve("out.txt")));
    }

    @Test
    void linesTypedDuringARunAreCommandsOrSteering() throws Throwable {
        CliSession s = cli().start(args());
        s.await("default> ");
        s.type("SLOW one");
        assertTrue(server.slowArrived.await(30, TimeUnit.SECONDS), "the run reached the model");

        s.type("/where");
        s.await("working directory: ");
        s.type("/expand");
        s.await("No tool call to expand.");
        s.type("/exit");
        s.await("The agent is running. Stop it and exit the whole process? [y/N] ");
        s.type("no");
        s.await("Continuing.");
        s.type("change of plan");
        s.await("[queued steer] change of plan");

        server.release();
        s.await("slow done");
        s.await("default> ");
        s.end();
        assertTrue(server.agentRequests().stream().anyMatch(r -> r.toString().contains("change of plan")),
                "the steering text reached the model");
    }

    @Test
    void cancelStopsTheRun() throws Throwable {
        CliSession s = cli().start(args());
        s.await("default> ");
        s.type("SLOW two");
        assertTrue(server.slowArrived.await(30, TimeUnit.SECONDS));
        s.type("/cancel");
        s.await("[cancelled]");
        s.await("default> ");
        server.release();
        s.type("after the cancel");
        s.await("echo: after the cancel");
        s.end();
    }

    // ---------------------------------------------------------------- resume

    @Test
    void resumeByIdReplaysTheChatAndRetentionDeletesOldChats() throws Throwable {
        CliSession first = cli().start(args());
        String id = chatIdIn(first.await("agent-harness chat "));
        first.await("default> ");
        first.type("remember this");
        first.await("echo: remember this");
        first.await("default> ");
        first.end();

        CliSession second = cli().start(args("--resume", id.substring(0, 12), "--prompt", "and this"));
        second.await("resumed chat " + id);
        second.await("[user] remember this");
        second.await("[assistant] echo: remember this");
        second.await("echo: and this");
        second.end();
        assertTrue(lastUserText(server.agentRequests().get(server.agentRequests().size() - 1)).contains("and this"));
        assertTrue(server.agentRequests().get(server.agentRequests().size() - 1).toString().contains("remember this"),
                "the resumed chat keeps its history");

        // a new chat beside the old one: with a limit of 1, the old one goes
        Path settings = Files.writeString(tmp.resolve("limit.json"), "{\"chatRetentionLimit\": 1}");
        CliSession third = cli().start(args("--resume", "no", "--settings", settings.toString()));
        third.await("chatRetentionLimit: deleted 1 old chat(s)");
        third.await("default> ");
        int from = third.output().length();
        third.type("/chats");
        third.await("default> ");
        String list = third.output().substring(from);
        assertTrue(list.contains("* "), list);
        assertFalse(list.contains(id), list);
        third.end();
    }

    @Test
    void interactiveStartOffersTheSavedChat() throws Throwable {
        CliSession first = cli().start(args("--prompt", "saved before"));
        first.await("echo: saved before");
        first.end();

        CliSession second = cli().start(args());
        second.await("Previous chat ");
        second.await("Continue it? [Y/n] ");
        second.type("");
        second.await("resumed chat ");
        second.await("[user] saved before");
        second.await("default> ");
        second.end();
    }
}
