package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.commands.SlashCommands;
import org.kendar.llm.harness.context.mnemonic.FileMnemonicStore;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.sandbox.CwdSandboxExecutor;
import org.kendar.llm.harness.session.FileChatSessionStore;
import org.kendar.llm.harness.session.FileHistorySubAgentRunLog;
import org.kendar.llm.harness.session.HistoryRootLock;
import org.kendar.llm.harness.session.InMemorySubAgentRunLog;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Command line front end: an interactive chat (or a single {@code --prompt}) over the standard tools.
 *
 * <pre>
 * java -cp ... org.kendar.llm.harness.Main [--provider anthropic|openai|gemini|grok|qwen|llamacpp|ollama] [--model M]
 *     [--base-url U] [--api-key K] [--mode plan|edit|auto] [--workdir DIR] [--prompt TEXT]
 *     [--settings FILE] [--system-prompt TEXT] [--system-prompt-file FILE] [--system-prompt-replace FILE] [--max-turns N] [--context-tokens TOKENS]
 *     [--search-api-key BRAVE_KEY] [--context-strategy compaction|mnemonic|scope] [--max-tokens TOKENS] [--small-model]
 *     [--resume prompt|no|CHAT_ID]
 * </pre>
 * {@code --max-tokens} is the output limit of one model response (default 32000);
 * {@code --context-tokens} the window. {@code --small-model} (no value) adds the stricter small-model rules to the
 * system prompt, like settings {@code "smallModel": true}.
 * The API key defaults to ANTHROPIC_API_KEY / OPENAI_API_KEY / QWEN_API_KEY; {@code --api-key ASK_TOKEN} asks for it
 * on startup, before the chat begins (hidden when run in a terminal).
 * web_search needs a Brave Search key: --search-api-key or BRAVE_API_KEY. Interactive commands: see /help;
 * Ctrl+C cancels the run. A line typed while a run is active steers it;
 * {@code /followup <text>} queues a message for when the agent would finish.
 */
public final class Main {

    private static final AgentEvent END = new AgentEvent(-1, "end", null);
    /** {@code --api-key} value that makes the CLI ask for the key instead of taking it from the command line. */
    static final String ASK_TOKEN = "ASK_TOKEN";
    /** Default output limit of one response, all providers. */
    

    /** The JLine terminal, null when stdin/stdout is not a terminal (plain output and input). */
    private static Term term;
    private static Renderer renderer;
    private static volatile boolean running;
    /** A run_js call of the model is in flight: stopping the run asks how to stop the script. */
    private static volatile boolean scriptRunning;
    private static volatile boolean stopPending;
    private static volatile long stopAsked;
    private static final long STOP_ANSWER_MILLIS = 30_000;
    private static volatile long runStart;
    private static volatile long lastInterrupt;

    private Main() {
    }

    private static void say(String s) {
        if (term != null) term.println(s);
        else System.out.println(s);
    }

    private static void sayErr(String s) {
        if (term != null) term.println(s);
        else System.err.println(s);
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> a = CliArgs.parse(argv);
        if (a.containsKey("help")) {
            System.out.println(CliArgs.USAGE);
            return;
        }
        if (ASK_TOKEN.equals(a.get("api-key"))) {
            String key = askToken();
            if (key.isEmpty()) throw new IllegalArgumentException("No API key given");
            a.put("api-key", key);
        }
        ObjectMapper mapper = new ObjectMapper();
        Path projectDir = Path.of(a.getOrDefault("workdir", "")).toAbsolutePath().normalize();
        // global ~/.jllm/settings.json, then project .jllm/settings.json, then --settings; later wins
        // a stdio MCP server defined only by the project file starts a local process: ask once (answer remembered)
        boolean canAsk = !a.containsKey("prompt") && System.in.available() == 0;
        HarnessSettings settings = HarnessSettings.loadLayered(
                new org.kendar.llm.harness.settings.FileSettingsSource(
                        a.containsKey("settings") ? Path.of(a.get("settings")) : null, projectDir),
                projectDir, System::getenv,
                (dir, server, cmd) -> canAsk ? askYesNo("The project settings of " + dir + " define "
                        + (server.startsWith(HarnessSettings.STOP_HOOK_TRUST_PREFIX) ? "a stop hook"
                        : "the MCP server '" + server + "'") + ", which runs the local command:\n  " + cmd
                        + "\nRun it?") : null,
                org.kendar.llm.harness.settings.FileProjectMcpTrust.user(),
                org.slf4j.LoggerFactory.getLogger(Main.class)::warn);
        var promptFiles = org.kendar.llm.harness.instructions.SystemPromptFiles.load(projectDir);
        String promptReplace = a.containsKey("system-prompt-replace")
                ? org.kendar.llm.harness.instructions.SystemPromptFiles.readFile(Path.of(a.get("system-prompt-replace")))
                : promptFiles.replace();
        String promptAppend = org.kendar.llm.harness.instructions.SystemPromptFiles.join(
                org.kendar.llm.harness.instructions.SystemPromptFiles.join(promptFiles.append(),
                        a.containsKey("system-prompt-file")
                                ? org.kendar.llm.harness.instructions.SystemPromptFiles.readFile(Path.of(a.get("system-prompt-file")))
                                : null),
                a.get("system-prompt"));
        // the flag wins over the settings file
        settings = SettingsOverrides.applyToSettings(a, settings);
        // the agent works in the project directory; scratch directories go under the settings' sandbox.tempDir
        HarnessConfig config = SettingsOverrides.applyToConfig(a, settings.applyTo(HarnessConfig.defaults()),
                System.getenv("BRAVE_API_KEY"));

        if (a.containsKey("sandbox-clean") || a.containsKey("sandbox-clean-all")) {
            cleanSandbox(config, projectDir, a.containsKey("sandbox-clean-all"));
            return;
        }
        CwdSandboxExecutor cwdSandbox = new CwdSandboxExecutor(config, projectDir);
        HistoryRootLock historyLock = HistoryRootLock.acquire(cwdSandbox.historyRoot()); // held until the process ends
        var dockerRunner = new org.kendar.llm.harness.sandbox.ProcessDockerCommandRunner();
        org.kendar.llm.harness.sandbox.SandboxExecutor sandbox = !settings.dockerSandbox() ? cwdSandbox
                : settings.dockerDaemon()
                ? new org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor(config, dockerRunner, cwdSandbox,
                org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.Kind.CLI, projectDir)
                .onWarning(w -> System.err.println("warning: " + w))
                : new org.kendar.llm.harness.sandbox.DockerSandboxExecutor(config, dockerRunner, cwdSandbox);
        ProviderChooser.ProviderChoice choice = ProviderChooser.choose(a, settings, mapper, config.maxTokens());
        AgentHarness.Builder builder = AgentHarness.builder();
        if (a.containsKey("plugin-dir")) builder.pluginDir(Path.of(a.get("plugin-dir")));
        else if (java.nio.file.Files.isDirectory(Path.of("plugins"))) builder.pluginDir(Path.of("plugins"));
        AgentHarness harness = builder
                .modelClient(choice.client())
                .activeProvider(choice.name())
                .sessionProvider(choice.session())
                .config(config)
                .systemPrompt(promptAppend)
                .systemPromptReplace(promptReplace)
                .sandbox(sandbox)
                .sessionStore(new FileChatSessionStore(cwdSandbox.historyRoot(), mapper))
                .subAgentRunLog(new FileHistorySubAgentRunLog(new InMemorySubAgentRunLog(), cwdSandbox.historyRoot()))
                .mnemonicStore(new FileMnemonicStore(cwdSandbox.historyRoot()))
                .stateStore(new org.kendar.llm.harness.state.FileStateStore(cwdSandbox.historyRoot()))
                .settings(settings)
                .pricing(new org.kendar.llm.harness.HarnessPricing(CliArgs.num(a, "pricing.input-per-million"),
                        CliArgs.num(a, "pricing.output-per-million"), CliArgs.num(a, "pricing.cache-read-per-million"),
                        CliArgs.num(a, "pricing.cache-write-per-million")))
                .objectMapper(mapper)
                .discoverPlugins()
                .build();
        List<String> names = new java.util.ArrayList<>(harness.commands().list().stream().map(SlashCommands.Info::name).toList());
        names.add("/expand");
        term = a.containsKey("prompt") ? null : Term.open(names, cwdSandbox.historyRoot().resolve("cli-history"));
        renderer = new Renderer(Main::say, Main::sayErr, term != null);
        say("project dir: " + harness.sandbox().location() + (settings.dockerSandbox()
                ? " (shell commands run in docker image " + config.sandbox().docker().image()
                + (settings.dockerDaemon() ? ", long-running container, project mounted at /workspace)" : ")")
                : " (no isolation: shell commands run as you, plan mode cannot stop them writing here)"));
        say("scratch dir: " + cwdSandbox.scratchRoot());
        say("history dir: " + cwdSandbox.historyRoot());
        PermissionMode initialMode = PermissionMode.parse(a.get("mode"));
        BlockingQueue<AgentEvent> events = new LinkedBlockingQueue<>();
        Chat chat = new Chat();
        // piped input is the script, not an answer: ask only when nothing is waiting on stdin
        boolean interactive = !a.containsKey("prompt") && System.in.available() == 0;
        BlockingQueue<String> in = term == null ? stdin() : new LinkedBlockingQueue<>();
        Term.Tail tail = term == null ? null : new Term.Tail(term);
        String resumeId = ResumeChooser.choose(harness, a.get("resume"), interactive, () -> {
            if (term != null) return term.readLine(tail.get(), () -> { });
            try {
                String l = in.take();
                return l.equals(LineCommands.EOF) ? null : l;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }, term == null ? System.out : tail);
        if (tail != null) tail.flush();
        String openId = resumeId != null ? resumeId : harness.sessions().create(null).chatId();
        int deleted = harness.enforceChatRetention(settings.chatRetentionLimit(), openId);
        if (deleted > 0) say("chatRetentionLimit: deleted " + deleted + " old chat(s)");
        switchTo(harness, chat, openId, resumeId == null || a.containsKey("mode") ? initialMode : null, events);
        if (resumeId != null) {
            say("resumed chat " + openId);
            ResumeChooser.replay(harness, openId, term == null ? System.out : new Term.Tail(term));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            harness.cancel(chat.id);
            harness.close();
            historyLock.close();
        }));

        if (a.containsKey("prompt")) {
            runTurn(harness, chat.id, a.get("prompt"), events, in);
            System.exit(0);
        }
        say("agent-harness chat " + chat.id + " (type /help for commands, /exit to quit; /cancel or ctrl+c stops a run)");
        if (term != null) startTerminalUi(harness, chat, choice.name(), in);
        while (true) {
            if (term == null) {
                System.out.print(harness.sessions().get(chat.id).mode().toLowerCase() + "> ");
                System.out.flush();
            }
            String line = in.take();
            if (line.equals(LineCommands.EOF)) break;
            if (line.equals(LineCommands.INTERRUPTED)) continue;
            if (LineCommands.isExitCommand(line)) {
                ask("Stop the whole process and exit? [y/N] ");
                String answer = in.take();
                if (answer.equals(LineCommands.EOF) || LineCommands.startsWithYes(answer)) break;
                continue;
            }
            if (harness.commands().menuActive(chat.id)) {
                menu(harness.commands().menuInput(chat.id, line));
                continue;
            }
            if (line.isBlank()) continue;
            if (LineCommands.isExpandCommand(line)) {
                renderer.expand(LineCommands.expandArg(line));
                continue;
            }
            // nothing to steer or follow up while idle: the text is a normal prompt, and the run is followed here
            String queued = LineCommands.idleQueuedText(line);
            if (queued != null) {
                runTurn(harness, chat.id, queued, events, in);
                continue;
            }
            if (harness.commands().handles(line.strip())) {
                command(harness, chat, line.strip(), events);
                continue;
            }
            runTurn(harness, chat.id, line, events, in);
        }
        System.exit(0);
    }

    /** Chat and id of the approval the CLI is asking about now, or null. */
    private static volatile String[] pendingApproval;

    /** Set while a /exit typed during a run waits for its y/N answer (the next typed line). */
    private static boolean exitPending;

    /** Line reader thread (the prompt stays up while the agent runs), key bindings and the status bar. */
    private static void startTerminalUi(AgentHarness harness, Chat chat, String provider, BlockingQueue<String> in) {
        PermissionMode[] cycle = {PermissionMode.DEFAULT, PermissionMode.EDIT, PermissionMode.PLAN, PermissionMode.AUTO};
        term.bind("\u001b[Z", () -> { // shift+tab: next permission mode
            PermissionMode cur = PermissionMode.parse(harness.sessions().get(chat.id).mode());
            int i = java.util.Arrays.asList(cycle).indexOf(cur);
            harness.sessions().setMode(chat.id, cycle[(i + 1) % cycle.length]);
            drawStatus(harness, chat, provider);
        });
        term.bind("\u000f", () -> renderer.expand("")); // ctrl+o: the last tool call in full
        Thread reader = getThread(harness, chat, in);
        reader.start();
        Thread bar = new Thread(() -> {
            while (true) {
                drawStatus(harness, chat, provider);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "status-bar");
        bar.setDaemon(true);
        bar.start();
    }

    private static @NonNull Thread getThread(AgentHarness harness, Chat chat, BlockingQueue<String> in) {
        Thread reader = new Thread(() -> {
            while (true) {
                String l = term.readLine("> ", () -> {
                    long now = System.currentTimeMillis();
                    if (rejectPendingApproval(harness, in)) {
                        lastInterrupt = 0; // a second ctrl+c stops the run, it does not exit
                        return;
                    }
                    if (running) requestStop(harness, chat.id);
                    else if (now - lastInterrupt < 2000) System.exit(0);
                    else say(Renderer.DIM + "(press ctrl+c again to exit)" + Renderer.RESET);
                    lastInterrupt = now;
                });
                in.add(l == null ? LineCommands.EOF : l);
                if (l == null) return;
            }
        }, "terminal-input");
        reader.setDaemon(true);
        return reader;
    }

    private static final String[] SPIN = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private static void drawStatus(AgentHarness harness, Chat chat, String provider) {
        int subs = harness.activeSubAgents(chat.id).size();
        String state = running
                ? Renderer.YELLOW + SPIN[(int) (System.currentTimeMillis() / 100 % SPIN.length)] + " working "
                + (System.currentTimeMillis() - runStart) / 1000 + "s"
                + (subs > 0 ? " · " + subs + " sub-agent" + (subs > 1 ? "s" : "") : "")
                + " (ctrl+c to interrupt)" + Renderer.RESET
                : Renderer.GREEN + "idle" + Renderer.RESET;
        term.status(Renderer.BOLD + harness.sessions().get(chat.id).mode().toLowerCase() + Renderer.RESET
                + " · " + provider + " · " + chat.id + " · " + state
                + Renderer.DIM + "  shift+tab mode · ctrl+o expand" + Renderer.RESET);
    }

    /** Reads stdin on a daemon thread, so lines typed while a run is active can be seen (e.g. /cancel). */
    /**
     * Reads the API key: hidden on a terminal, otherwise one plain line (e.g. the IDE console). The plain line is read
     * byte by byte so nothing typed after it is swallowed before the chat's own stdin reader starts.
     */
    private static String askToken() throws IOException {
        java.io.Console console = System.console();
        // up to JDK 21 System.console() is null unless stdin and stdout are a terminal (isTerminal() needs JDK 22)
        if (console != null) {
            char[] key = console.readPassword("API key: ");
            return key == null ? "" : new String(key).strip();
        }
        System.out.print("API key: ");
        System.out.flush();
        String line = LineCommands.readPlainLine(System.in);
        return line == null ? "" : line.strip();
    }

    /**
     * Asks a yes/no question before the chat starts: true for y/yes, false for anything else, null when stdin ended.
     * The plain line is read byte by byte (as {@link #askToken}) so the chat's own stdin reader loses nothing.
     */
    static Boolean askYesNo(String question) {
        try {
            java.io.Console console = System.console();
            if (console != null) return LineCommands.parseYesNo(console.readLine("%s [y/N] ", question));
            return LineCommands.askYesNo(question, System.out, System.in);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BlockingQueue<String> stdin() {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread t = new Thread(() -> {
            try (var r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String l = r.readLine(); l != null; l = r.readLine()) lines.add(l);
            } catch (IOException ignored) {
            }
            lines.add(LineCommands.EOF);
        }, "stdin");
        t.setDaemon(true);
        t.start();
        return lines;
    }

    /** The chat the REPL is attached to, and the handle that stops its event subscription. */
    private static final class Chat {
        String id;
        Runnable unsubscribe = () -> { };
    }

    private static void switchTo(AgentHarness harness, Chat chat, String chatId, PermissionMode mode,
                                 BlockingQueue<AgentEvent> events) {
        chat.unsubscribe.run();
        chat.id = chatId;
        if (mode != null) harness.sessions().setMode(chatId, mode);
        events.clear();
        chat.unsubscribe = harness.subscribe(chatId, 0, events::add);
    }

    /** Runs a slash command through the shared {@link SlashCommands}; follows a chat switch it asks for. */
    private static void command(AgentHarness harness, Chat chat, String line, BlockingQueue<AgentEvent> events) {
        var r = harness.commands().execute(chat.id, line);
        if (r.switchToChatId() != null) switchTo(harness, chat, r.switchToChatId(), null, events);
        menu(r);
    }

    private static void menu(SlashCommands.Result r) {
        if (r.output() != null && !r.output().isBlank()) say(r.output());
    }

    private static void runTurn(AgentHarness harness, String chatId, String text, BlockingQueue<AgentEvent> events,
                                BlockingQueue<String> in) throws InterruptedException {
        events.clear();
        runStart = System.currentTimeMillis();
        running = true;
        scriptRunning = false;
        stopPending = false;
        try {
            runLoop(harness, chatId, text, events, in);
        } finally {
            running = false;
            scriptRunning = false;
            stopPending = false;
        }
    }

    private static void runLoop(AgentHarness harness, String chatId, String text, BlockingQueue<AgentEvent> events,
                                BlockingQueue<String> in) throws InterruptedException {
        try {
            harness.start(chatId, text);
        } catch (org.kendar.llm.harness.agent.AgentService.RunAlreadyActiveException e) {
            sayErr(Renderer.RED + "[error] " + e.getMessage() + " (/cancel stops it)" + Renderer.RESET);
            return;
        }
        int open = 0; // runs started and not finished: a late steer or follow-up can start the next one
        while (true) {
            // Input typed during the run: a command (/cancel, /followup <text>) runs, any other line steers the agent
            for (String typed = in.poll(); typed != null; typed = in.poll()) {
                if (typed.equals(LineCommands.EOF) || typed.equals(LineCommands.INTERRUPTED) || typed.isBlank()) continue;
                if (stopPending) {
                    stopPending = false;
                    harness.cancel(chatId, typed.strip().toLowerCase().startsWith("k"));
                    continue;
                }
                if (exitPending) {
                    exitPending = false;
                    if (typed.strip().toLowerCase().startsWith("y")) {
                        harness.cancel(chatId);
                        System.exit(0);
                    }
                    say("Continuing.");
                    continue;
                }
                if (LineCommands.isExitCommand(typed)) {
                    ask("The agent is running. Stop it and exit the whole process? [y/N] ");
                    exitPending = true;
                    continue;
                }
                if (typed.strip().equals("/cancel")) requestStop(harness, chatId);
                else if (typed.strip().startsWith("/expand")) renderer.expand(LineCommands.expandArg(typed));
                else if (harness.commands().handles(typed.strip())) menu(harness.commands().execute(chatId, typed.strip()));
                else harness.steer(chatId, typed.strip());
            }
            if (stopPending && System.currentTimeMillis() - stopAsked > STOP_ANSWER_MILLIS) {
                stopPending = false; // no answer: kill, with no further question
                harness.cancel(chatId, true);
            }
            AgentEvent ev = events.poll(100, TimeUnit.MILLISECONDS);
            if (ev == null) continue;
            JsonNode d = ev.data();
            if (!d.has("parent") && "run_js".equals(d.path("name").asText())) {
                if (ev.type().equals("tool_call")) scriptRunning = true;
                else if (ev.type().equals("tool_result")) scriptRunning = false;
            }
            switch (ev.type()) {
                case "approval_request" -> approve(harness, chatId, d, in);
                case "run_started" -> open++;
                case "run_finished" -> {
                    open--;
                    // a message typed as the run ended started another one: follow it too
                    if (open <= 0 && !harness.isRunning(chatId) && events.isEmpty()) return;
                }
                default -> renderer.render(ev);
            }
        }
    }

    /**
     * Stops the run. While a script is running there are two ways: kill it now, or let it clean up (its next tool
     * call fails with a catchable error; it is killed after its grace period). No answer within 30 s kills it.
     */
    private static void requestStop(AgentHarness harness, String chatId) {
        if (!scriptRunning) {
            harness.cancel(chatId);
            return;
        }
        if (stopPending) return;
        stopPending = true;
        stopAsked = System.currentTimeMillis();
        ask("A script is running. Stop it how? [k=kill now / g=let it clean up first (default); no answer in 30 s kills it] ");
    }

    private static void approve(AgentHarness harness, String chatId, JsonNode d, BlockingQueue<String> in)
            throws InterruptedException {
        String id = d.path("id").asText();
        pendingApproval = new String[]{chatId, id};
        try {
            new ApprovalPrompter(harness::resolveApproval, Main::say, Main::ask, in::take,
                    Path.of("").toAbsolutePath()).prompt(chatId, d);
        } finally {
            pendingApproval = null;
        }
    }

    /**
     * Ctrl+C while an approval question is open: the call is rejected and whatever was typed for it is dropped.
     *
     * @return whether there was a question to reject
     */
    private static boolean rejectPendingApproval(AgentHarness harness, BlockingQueue<String> in) {
        String[] p = pendingApproval;
        if (p == null) return false;
        pendingApproval = null;
        harness.resolveApproval(p[0], p[1], false, null, false, false);
        in.clear();
        in.add(LineCommands.INTERRUPTED);
        say(Renderer.YELLOW + "[approval] rejected (ctrl+c again stops the run)" + Renderer.RESET);
        return true;
    }

    /** A question: above the prompt on a terminal (the answer comes from the input line), inline otherwise. */
    private static void ask(String question) {
        if (term != null) {
            say(Renderer.BOLD + question + Renderer.RESET);
        } else {
            System.out.print(question);
            System.out.flush();
        }
    }

    /** {@code --sandbox-clean[-all]}: removes the docker daemon-mode container(s), prints what went, and exits. */
    private static void cleanSandbox(HarnessConfig config, Path projectDir, boolean all) throws IOException {
        var runner = new org.kendar.llm.harness.sandbox.ProcessDockerCommandRunner();
        List<String> removed = all
                ? org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.cleanAllCli(config, runner)
                : org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.cleanStartupDir(config, runner, projectDir);
        if (removed.isEmpty()) System.out.println("No sandbox container to remove" + (all ? "." : " for " + projectDir));
        else removed.forEach(n -> System.out.println("removed " + n));
        System.out.println("Project files and .jllm are untouched.");
    }
}
