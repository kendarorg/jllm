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
        cli = new CliSession(home).trusting(project);
        return cli;
    }

    /** A session whose project folder was never trusted. */
    private CliSession untrustedCli() {
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
        assertTrue(Files.readString(home.resolve(".jllm").resolve("trusted.json")).contains("local-tool"),
                "the answer is remembered");
    }

    // ---------------------------------------------------------------- folder trust

    /** What must not happen before the folder is trusted: nothing of it is read, started or loaded. */
    private void projectWithEverything() throws Exception {
        Path jllm = Files.createDirectories(project.resolve(".jllm"));
        Files.writeString(jllm.resolve("settings.json"), """
                {"mcpServers": [{"name": "local-tool", "command": "jllm-no-such-command"}],
                 "permissions": {"allow": ["Bash(*)"]}}
                """);
        Files.writeString(jllm.resolve("SYSTEM.md"), "PROJECT-SYSTEM-PROMPT");
        Files.writeString(project.resolve("AGENTS.md"), "PROJECT-INSTRUCTIONS");
    }

    @Test
    void decliningTrustExitsWithAWarningAndLoadsNothing() throws Throwable {
        projectWithEverything();
        CliSession s = untrustedCli().start(args());
        s.await("is not trusted yet");
        s.await("Trust this folder? [y/N] ");
        s.type("n");
        assertEquals(RunReport.EXIT_FAILED, s.exitCode());
        String out = s.output();
        assertTrue(out.contains("not trusted"), out);
        assertFalse(out.contains("jllm-no-such-command --x"), "no MCP prompt: " + out);
        assertTrue(server.requests.isEmpty(), "no session started, no model call");
        assertFalse(Files.exists(project.resolve(".jllm/scratchpad")), "no scratch or history dir was created");
        assertFalse(Files.exists(project.resolve(".jllm/history")));
        assertFalse(Files.exists(home.resolve(".jllm").resolve("trusted.json")), "the refusal is not stored");
        cli.close();
        // the next start asks again
        CliSession again = untrustedCli().start(args());
        again.await("Trust this folder? [y/N] ");
        again.type("n");
        assertEquals(RunReport.EXIT_FAILED, again.exitCode());
    }

    @Test
    void trustedFolderThenAChangeInJllmAsksAgain() throws Throwable {
        projectWithEverything();
        CliSession s = untrustedCli().start(args("--prompt", "hi"));
        // unattended: refused, nothing read
        assertEquals(RunReport.EXIT_FAILED, s.exitCode());
        cli.close();
        CliSession yes = untrustedCli().start(args());
        yes.await("Trust this folder? [y/N] ");
        yes.type("y");
        yes.await("Use it? [y/N] ");
        yes.type("n");
        yes.await("define the MCP server 'local-tool'");
        yes.await("Run it? [y/N] ");
        yes.type("n");
        yes.await("Apply it? [y/N] ");
        yes.type("n");
        yes.await("agent-harness chat ");
        yes.end();
        cli.close();
        Files.writeString(project.resolve(".jllm/settings.json"), "{\"changed\": true}");
        CliSession changed = untrustedCli().start(args());
        changed.await("changed since you trusted it");
        changed.type("n");
        assertEquals(RunReport.EXIT_FAILED, changed.exitCode());
    }

    @Test
    void unattendedRunOnAnUntrustedFolderExitsWithAWarning() throws Throwable {
        projectWithEverything();
        CliSession s = untrustedCli().start(args("--prompt", "hello"));
        assertEquals(RunReport.EXIT_FAILED, s.exitCode());
        assertTrue(s.output().contains("--trust-project"), s.output());
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void trustProjectFlagAndTrustedProjectsLetItRun() throws Throwable {
        CliSession flag = untrustedCli().start(args("--prompt", "via flag", "--trust-project"));
        flag.await("echo: via flag");
        assertEquals(0, flag.exitCode());
        cli.close();
        Files.createDirectories(home.resolve(".jllm"));
        Files.writeString(home.resolve(".jllm/settings.json"),
                "{\"trustedProjects\": [" + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(project.toString()) + "]}");
        CliSession listed = untrustedCli().start(args("--prompt", "via list"));
        listed.await("echo: via list");
        assertEquals(0, listed.exitCode());
    }

    /** A project whose MCP server, when started, writes {@code started} next to the project; and the instruction files. */
    private Path projectThatLeavesTracesWhenStarted() throws Exception {
        Path src = tmp.resolve("Marker.java");
        Files.writeString(src, "public class Marker { public static void main(String[] a) throws Exception {"
                + " java.nio.file.Files.writeString(java.nio.file.Path.of(a[0]), \"started\"); } }");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path jllm = Files.createDirectories(project.resolve(".jllm"));
        var servers = JSON.createObjectNode().putArray("mcpServers").addObject().put("name", "tracer")
                .put("command", java.toString());
        servers.putArray("args").add(src.toString()).add(tmp.resolve("started").toString());
        Files.writeString(jllm.resolve("settings.json"), JSON.createObjectNode().set("mcpServers",
                JSON.createArrayNode().add(servers)).toString());
        return tmp.resolve("started");
    }

    @Test
    void nothingOfAnUntrustedFolderStartsBeforeTheAnswerNorAfterARefusal() throws Throwable {
        Path started = projectThatLeavesTracesWhenStarted();
        CliSession s = untrustedCli().start(args());
        s.await("Trust this folder? [y/N] ");
        Thread.sleep(3000); // a server started before the answer would have left its mark by now
        assertFalse(Files.exists(started), "no MCP server starts while the folder is undecided");
        s.type("n");
        assertEquals(RunReport.EXIT_FAILED, s.exitCode());
        assertFalse(s.output().contains("tracer"), "the server was not even asked about: " + s.output());
        cli.close();
        CliSession unattended = untrustedCli().start(args("--prompt", "hi"));
        assertEquals(RunReport.EXIT_FAILED, unattended.exitCode());
        Thread.sleep(3000);
        assertFalse(Files.exists(started), "no MCP server starts in a refused unattended run");
    }

    @Test
    void aTrustedFolderStartsItsApprovedMcpServer() throws Throwable {
        Path started = projectThatLeavesTracesWhenStarted();
        CliSession s = cli().start(args());
        s.await("define the MCP server 'tracer'");
        s.await("Run it? [y/N] ");
        s.type("y");
        long end = System.currentTimeMillis() + 30_000;
        while (!Files.exists(started) && System.currentTimeMillis() < end) Thread.sleep(100);
        assertTrue(Files.exists(started), "the probe works: an approved server does start:\n" + s.output());
        s.end();
    }

    private static final String[] INSTRUCTION_FILES = {"AGENTS.md", "CLAUDE.md", "KENDAR.md"};

    /** Instruction files in the project, listed by absolute path in the user's settings. */
    private void instructionFilesInTheProject() throws Exception {
        var names = JSON.createArrayNode();
        for (String f : INSTRUCTION_FILES) {
            Files.writeString(project.resolve(f), "MARK-" + f.replace(".md", "") + "-TEXT");
            names.add(project.resolve(f).toString());
        }
        Files.createDirectories(home.resolve(".jllm"));
        Files.writeString(home.resolve(".jllm/settings.json"),
                JSON.createObjectNode().set("instructionFiles", names).toString());
    }

    @Test
    void instructionFilesOfADeclinedFolderAreNeverReadIntoAPrompt() throws Throwable {
        instructionFilesInTheProject();
        CliSession s = untrustedCli().start(args());
        s.await("Trust this folder? [y/N] ");
        s.type("n");
        assertEquals(RunReport.EXIT_FAILED, s.exitCode());
        assertTrue(server.requests.isEmpty(), "no model call, so no prompt");
        for (String f : INSTRUCTION_FILES) {
            assertFalse(s.output().contains("MARK-" + f.replace(".md", "")), f + " leaked: " + s.output());
        }
        cli.close();
        CliSession unattended = untrustedCli().start(args("--prompt", "hello"));
        assertEquals(RunReport.EXIT_FAILED, unattended.exitCode());
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void instructionFilesOfATrustedFolderReachThePrompt() throws Throwable {
        instructionFilesInTheProject();
        CliSession s = cli().start(args("--prompt", "hello"));
        s.await("echo: hello");
        assertEquals(0, s.exitCode());
        String sys = systemText(server.agentRequests().get(0));
        for (String f : INSTRUCTION_FILES) {
            assertTrue(sys.contains("MARK-" + f.replace(".md", "") + "-TEXT"), f + " in the prompt: " + sys);
        }
    }

    @Test
    void skipPermissionsDoesNotTrustAnUntrustedFolder() throws Throwable {
        projectWithEverything();
        CliSession headless = untrustedCli().start(args("--prompt", "PLEASE WRITE now", "--dangerously-skip-permissions"));
        assertEquals(RunReport.EXIT_FAILED, headless.exitCode());
        assertTrue(headless.output().contains("--trust-project"), headless.output());
        assertTrue(server.requests.isEmpty());
        assertFalse(Files.exists(project.resolve("out.txt")));
        cli.close();
        CliSession attended = untrustedCli().start(args("--dangerously-skip-permissions"));
        attended.await("Trust this folder? [y/N] ");
        attended.type("n");
        assertEquals(RunReport.EXIT_FAILED, attended.exitCode());
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void skipPermissionsDoesNotChangeTheSandboxBackend() throws Throwable {
        CliSession plain = cli().start(args("--prompt", "one"));
        plain.await("echo: one");
        String plainBanner = bannerOf(plain.output());
        assertEquals(0, plain.exitCode());
        cli.close();
        CliSession skipping = cli().start(args("--prompt", "two", "--dangerously-skip-permissions"));
        skipping.await("echo: two");
        assertEquals(0, skipping.exitCode());
        assertEquals(plainBanner, bannerOf(skipping.output()), "same sandbox with the bypass");
        assertFalse(plainBanner.contains("in a container"), plainBanner);
        cli.close();
        CliSession docker = cli().start(args("--prompt", "three", "--sandbox", "docker",
                "--dangerously-skip-permissions"));
        docker.await("echo: three");
        assertTrue(bannerOf(docker.output()).contains("in a container"), "the flag of the backend still decides");
    }

    private static String bannerOf(String output) {
        return output.lines().filter(l -> l.startsWith("project dir:"))
                .map(l -> l.replaceFirst("project dir: \\S+", "")).findFirst().orElse("");
    }

    @Test
    void unapprovedLooseningInATrustedFolderHasNoEffectUnattended() throws Throwable {
        Path jllm = Files.createDirectories(project.resolve(".jllm"));
        Files.writeString(jllm.resolve("settings.json"), "{\"permissions\": {\"allow\": [\"Bash(*)\"]}}");
        Files.writeString(jllm.resolve("SYSTEM.md"), "PROJECT-SYSTEM-PROMPT");
        Files.writeString(project.resolve("AGENTS.md"), "PROJECT-INSTRUCTIONS");
        CliSession s = cli().start(args("--prompt", "hello"));
        s.await("echo: hello");
        assertEquals(0, s.exitCode());
        String sys = systemText(server.agentRequests().get(0));
        assertFalse(sys.contains("PROJECT-SYSTEM-PROMPT"), "SYSTEM.md is a loosening: not approved, not used");
        assertTrue(s.output().contains("loosens") || s.output().contains("SYSTEM.md"), s.output());
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

    // ---------------------------------------------------------------- headless contract

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void outcomesMapToExitCodes() {
        assertEquals(0, RunReport.exitCode("completed"));
        assertEquals(1, RunReport.exitCode("failed"));
        assertEquals(1, RunReport.exitCode("refused"));
        assertEquals(2, RunReport.exitCode("stuck"));
        assertEquals(3, RunReport.exitCode("max_turns"));
        assertEquals(3, RunReport.exitCode("truncated"));
        assertEquals(3, RunReport.exitCode("unverified"));
        assertEquals(4, RunReport.exitCode("cancelled"));
        assertEquals(1, RunReport.exitCode(null));
        assertEquals(1, RunReport.exitCode("something-new"));
        assertEquals(64, RunReport.EXIT_USAGE);
    }

    @Test
    void aCompletedPromptExitsZeroAndWritesTheResult() throws Throwable {
        Path result = tmp.resolve("out").resolve("result.json");
        CliSession s = cli().startProcess(args("--mode", "auto", "--prompt", "PLEASE WRITE now",
                "--json-result", result.toString(),
                "--pricing.input-per-million=1000000", "--pricing.output-per-million=2000000"));
        assertEquals(0, s.exitCode(), s.output());
        assertEquals(FakeOpenAiServer.WRITTEN, Files.readString(project.resolve("out.txt")));

        JsonNode r = JSON.readTree(result.toFile());
        assertEquals(List.of("chatId", "outcome", "turns", "durationMs", "usage", "costUsd", "toolCalls", "dangerouslySkipPermissions", "denied", "deniedCount", "error"),
                fieldNames(r));
        assertFalse(r.path("chatId").asText().isEmpty());
        assertEquals("completed", r.path("outcome").asText());
        assertEquals(2, r.path("turns").asInt(), r.toString());
        assertTrue(r.path("durationMs").asLong() >= 0);
        JsonNode u = r.path("usage");
        assertEquals(List.of("input", "output", "cacheRead", "cacheCreation"), fieldNames(u));
        assertTrue(u.path("input").asLong() >= 20, r.toString());
        assertTrue(u.path("output").asLong() >= 10, r.toString());
        assertEquals(u.path("input").asLong() + 2.0 * u.path("output").asLong(), r.path("costUsd").asDouble(), 1e-9);
        assertEquals(1, r.path("toolCalls").size(), r.toString());
        JsonNode call = r.path("toolCalls").get(0);
        assertEquals(List.of("name", "input", "isError"), fieldNames(call));
        assertEquals("write_file", call.path("name").asText());
        assertEquals("out.txt", call.path("input").path("path").asText());
        assertFalse(call.path("isError").asBoolean(true));
        assertTrue(r.path("error").isNull(), r.toString());
    }

    @Test
    void theTurnLimitExitsThree() throws Throwable {
        Path result = tmp.resolve("result.json");
        CliSession s = cli().startProcess(args("--mode", "auto", "--max-turns", "1", "--prompt", "PLEASE WRITE now",
                "--json-result", result.toString()));
        assertEquals(3, s.exitCode(), s.output());
        assertEquals("max_turns", JSON.readTree(result.toFile()).path("outcome").asText());
    }

    @Test
    void theTimeoutCancelsTheRunAndExitsFour() throws Throwable {
        Path result = tmp.resolve("result.json");
        CliSession s = cli().startProcess(args("--prompt", "SLOW timed", "--timeout", "1",
                "--json-result", result.toString()));
        assertEquals(4, s.exitCode(), s.output());
        assertTrue(s.output().contains("[timeout]"), s.output());
        JsonNode r = JSON.readTree(result.toFile());
        assertEquals("cancelled", r.path("outcome").asText());
        assertEquals("timeout after 1 s", r.path("error").asText());
        assertTrue(r.path("durationMs").asLong() >= 1000, r.toString());
    }

    @Test
    void aFailedModelCallExitsOne() throws Throwable {
        Path result = tmp.resolve("result.json");
        String[] a = args("--prompt", "hi", "--json-result", result.toString());
        server.close(); // nothing answers on the port any more
        CliSession s = cli().startProcess(a);
        assertEquals(1, s.exitCode(), s.output());
        JsonNode r = JSON.readTree(result.toFile());
        assertEquals("failed", r.path("outcome").asText());
        assertFalse(r.path("error").isNull(), r.toString());
    }

    @Test
    void usageErrorsExit64AndStillWriteTheResult() throws Throwable {
        Path result = tmp.resolve("result.json");
        CliSession s = cli().startProcess(args("--sandbox", "none", "--prompt", "x",
                "--json-result", result.toString()));
        assertEquals(64, s.exitCode(), s.output());
        JsonNode r = JSON.readTree(result.toFile());
        assertEquals("failed", r.path("outcome").asText());
        assertTrue(r.path("error").asText().contains("--mode auto"), r.toString());
        assertTrue(server.requests.isEmpty());
        s.close();

        assertEquals(64, cli().startProcess("stray", "--json-result", result.toString()).exitCode());
        assertTrue(JSON.readTree(result.toFile()).path("error").asText().contains("stray"));
        cli.close();
        assertEquals(64, cli().startProcess(args("--timeout", "5")).exitCode(), "--timeout needs --prompt");
        cli.close();
        assertEquals(64, cli().startProcess(args("--prompt", "x", "--timeout", "0")).exitCode());
        cli.close();
        assertEquals(0, cli().startProcess("--help").exitCode());
    }

    @Test
    void sandboxNoneWritesOutsideTheWorkdirAndLocalKeepsItsJail() throws Throwable {
        Path outside = Files.createDirectories(tmp.resolve("elsewhere")).resolve("app").resolve("x.txt");
        Path result = tmp.resolve("result.json");
        CliSession jailed = cli().startProcess(args("--mode", "auto", "--prompt", FakeOpenAiServer.WRITE_TO + outside,
                "--json-result", result.toString()));
        assertEquals(0, jailed.exitCode(), jailed.output());
        assertFalse(Files.exists(outside), "the local runner keeps its jail");
        assertTrue(JSON.readTree(result.toFile()).path("toolCalls").get(0).path("isError").asBoolean());
        jailed.close();

        CliSession open = cli().startProcess(args("--mode", "auto", "--sandbox", "none",
                "--prompt", FakeOpenAiServer.WRITE_TO + outside, "--json-result", result.toString()));
        assertEquals(0, open.exitCode(), open.output());
        assertTrue(open.output().contains("sandbox none"), open.output());
        assertEquals(FakeOpenAiServer.WRITTEN, Files.readString(outside));
        assertFalse(JSON.readTree(result.toFile()).path("toolCalls").get(0).path("isError").asBoolean(true));
    }

    @Test
    void promptRunDeniesWhatWouldAskAndReportsIt() throws Throwable {
        Path result = tmp.resolve("denied.json");
        CliSession s = cli().startProcess(args("--prompt", "PLEASE WRITE now", "--json-result", result.toString()));
        s.exitCode();
        assertFalse(Files.exists(project.resolve("out.txt")), "an unattended run asks no one: nothing written");
        assertTrue(s.output().contains("[denied] write_file"), s.output());
        assertFalse(s.output().contains("Allow?"), s.output());
        JsonNode r = JSON.readTree(result.toFile());
        assertEquals(1, r.path("deniedCount").asInt());
        assertEquals("write_file", r.path("denied").get(0).path("tool").asText());
        assertEquals("unattended", r.path("denied").get(0).path("reason").asText());
        assertFalse(r.path("dangerouslySkipPermissions").asBoolean());
        assertTrue(server.agentRequests().stream().anyMatch(q -> q.toString()
                .contains("denied: unattended run, no one can approve write_file")), "the model got the denial");
    }

    @Test
    void dangerouslySkipPermissionsRunsAToolThatWouldAskAndSaysSo() throws Throwable {
        Path result = tmp.resolve("skip.json");
        CliSession s = cli().startProcess(args("--prompt", "PLEASE WRITE now", "--dangerously-skip-permissions",
                "--json-result", result.toString()));
        assertEquals(0, s.exitCode(), s.output());
        assertEquals(FakeOpenAiServer.WRITTEN, Files.readString(project.resolve("out.txt")));
        assertTrue(s.output().contains("--dangerously-skip-permissions"), "banner: " + s.output());
        JsonNode r = JSON.readTree(result.toFile());
        assertTrue(r.path("dangerouslySkipPermissions").asBoolean());
        assertEquals(0, r.path("deniedCount").asInt());
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
