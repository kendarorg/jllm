package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.commands.SlashCommands;
import org.kendar.llm.harness.context.mnemonic.FileMnemonicStore;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ProviderCatalog;
import org.kendar.llm.harness.llm.ProviderSettings;
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
import java.util.HashMap;
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
    static final int DEFAULT_MAX_TOKENS = 32_000;

    /** The JLine terminal, null when stdin/stdout is not a terminal (plain output and input). */
    private static Term term;
    private static Renderer renderer;
    private static volatile boolean running;
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
        Map<String, String> a = parse(argv);
        if (a.containsKey("help")) {
            System.out.println("Usage: Main [--provider anthropic|openai|gemini|grok|qwen|llamacpp|ollama] [--model M] [--base-url U] "
                    + "[--api-key K] [--mode plan|edit|auto] [--workdir DIR] [--prompt TEXT] "
                    + "[--system-prompt TEXT] [--max-turns N] [--context-tokens TOKENS] [--search-api-key BRAVE_KEY] "
                    + "[--settings FILE] [--context-strategy compaction|mnemonic|scope] [--max-tokens TOKENS] [--small-model] "
                    + "[--resume prompt|no|CHAT_ID] "
                    + "[--sandbox local|docker] [--docker-image IMAGE] [--docker-mode oneshot|daemon] "
                    + "[--docker-remote] [--docker-remote-host ADDRESS] [--sandbox-clean] [--sandbox-clean-all] "
                    + "[--pricing.input-per-million=USD] [--pricing.output-per-million=USD] [--pricing.cache-read-per-million=USD] [--pricing.cache-write-per-million=USD] [--plugin-dir DIR] [--plugins.ID.enabled=true|false] [--plugins.ID.settings.KEY=VALUE]");
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
        HarnessSettings settings = HarnessSettings.loadLayered(
                a.containsKey("settings") ? Path.of(a.get("settings")) : null, projectDir);
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
        if (a.containsKey("context-tokens") && Integer.parseInt(a.get("context-tokens")) > 0) {
            settings = settings.withMaxTokens(Integer.parseInt(a.get("context-tokens"))); // <= 0: default, as the web app
        }
        if (a.containsKey("context-strategy")) settings = settings.withContextStrategy(a.get("context-strategy"));
        if (flag(a, "small-model")) settings = settings.withSmallModel(true);
        if (a.containsKey("sandbox")) settings = settings.withSandboxBackend(a.get("sandbox"));
        if (a.containsKey("docker-image")) settings = settings.withDockerImage(a.get("docker-image"));
        if (a.containsKey("docker-mode")) settings = settings.withDockerMode(a.get("docker-mode"));
        if (flag(a, "docker-remote") || a.containsKey("docker-remote-host")) {
            settings = settings.withDockerRemote(flag(a, "docker-remote") ? Boolean.TRUE : null,
                    a.get("docker-remote-host"));
        }
        for (var e : a.entrySet()) { // --plugins.<id>.enabled=true: this run only, as jllm.plugins.* in the web app
            if (e.getKey().startsWith("plugins.")) settings = settings.withPluginOption(e.getKey().substring(8), e.getValue());
        }
        HarnessConfig config = settings.applyTo(HarnessConfig.defaults());
        // the agent works in the project directory; scratch directories go under the settings' sandbox.tempDir
        String searchKey = a.getOrDefault("search-api-key", System.getenv("BRAVE_API_KEY"));
        if (searchKey != null) config = config.withSearchApiKey(searchKey);
        if (a.containsKey("max-turns") && Integer.parseInt(a.get("max-turns")) > 0) config = config.withMaxTurns(Integer.parseInt(a.get("max-turns")));
        config = config.withMaxTokens(a.containsKey("max-tokens") ? Integer.parseInt(a.get("max-tokens"))
                : DEFAULT_MAX_TOKENS);

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
        ProviderChoice choice = chooseProvider(a, settings, mapper, config.maxTokens());
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
                .pricing(new org.kendar.llm.harness.HarnessPricing(num(a, "pricing.input-per-million"),
                        num(a, "pricing.output-per-million"), num(a, "pricing.cache-read-per-million"),
                        num(a, "pricing.cache-write-per-million")))
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
                return l.equals(EOF) ? null : l;
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
            if (line.equals(EOF)) break;
            if (isExitCommand(line)) {
                ask("Stop the whole process and exit? [y/N] ");
                String answer = in.take();
                if (answer.equals(EOF) || answer.strip().toLowerCase().startsWith("y")) break;
                continue;
            }
            if (harness.commands().menuActive(chat.id)) {
                menu(harness.commands().menuInput(chat.id, line));
                continue;
            }
            if (line.isBlank()) continue;
            if (line.strip().equals("/expand") || line.strip().startsWith("/expand ")) {
                renderer.expand(line.strip().substring("/expand".length()));
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

    private static final String EOF = "<eof>";

    private static boolean isExitCommand(String line) {
        String s = line.strip();
        return s.equals("/exit") || s.equals("/quit");
    }

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
                    if (running) harness.cancel(chat.id);
                    else if (now - lastInterrupt < 2000) System.exit(0);
                    else say(Renderer.DIM + "(press ctrl+c again to exit)" + Renderer.RESET);
                    lastInterrupt = now;
                });
                in.add(l == null ? EOF : l);
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
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        for (int b = System.in.read(); b != -1 && b != '\n'; b = System.in.read()) line.write(b);
        return line.toString(StandardCharsets.UTF_8).strip();
    }

    private static BlockingQueue<String> stdin() {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread t = new Thread(() -> {
            try (var r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String l = r.readLine(); l != null; l = r.readLine()) lines.add(l);
            } catch (IOException ignored) {
            }
            lines.add(EOF);
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
        try {
            runLoop(harness, chatId, text, events, in);
        } finally {
            running = false;
        }
    }

    private static void runLoop(AgentHarness harness, String chatId, String text, BlockingQueue<AgentEvent> events,
                                BlockingQueue<String> in) throws InterruptedException {
        harness.start(chatId, text);
        while (true) {
            // Input typed during the run: a command (/cancel, /followup <text>) runs, any other line steers the agent
            for (String typed = in.poll(); typed != null; typed = in.poll()) {
                if (typed.equals(EOF) || typed.isBlank()) continue;
                if (exitPending) {
                    exitPending = false;
                    if (typed.strip().toLowerCase().startsWith("y")) {
                        harness.cancel(chatId);
                        System.exit(0);
                    }
                    say("Continuing.");
                    continue;
                }
                if (isExitCommand(typed)) {
                    ask("The agent is running. Stop it and exit the whole process? [y/N] ");
                    exitPending = true;
                    continue;
                }
                if (typed.strip().equals("/cancel")) harness.cancel(chatId);
                else if (typed.strip().startsWith("/expand")) renderer.expand(typed.strip().substring(7));
                else if (harness.commands().handles(typed.strip())) menu(harness.commands().execute(chatId, typed.strip()));
                else harness.steer(chatId, typed.strip());
            }
            AgentEvent ev = events.poll(100, TimeUnit.MILLISECONDS);
            if (ev == null) continue;
            JsonNode d = ev.data();
            switch (ev.type()) {
                case "approval_request" -> approve(harness, chatId, d, in);
                case "run_finished" -> {
                    return;
                }
                default -> renderer.render(ev);
            }
        }
    }

    private static void approve(AgentHarness harness, String chatId, JsonNode d, BlockingQueue<String> in)
            throws InterruptedException {
        say(Renderer.YELLOW + "[approval] " + d.path("type").asText() + " " + d.path("title").asText() + Renderer.RESET
                + "\n" + d.path("preview").asText());
        if ("continue".equals(d.path("type").asText())) {
            ask("Turns to allow [" + d.path("proposed").asText() + ", n = stop] ");
            String t = in.take();
            String v = t.equals(EOF) ? "n" : t.strip();
            boolean stop = v.toLowerCase().startsWith("n");
            harness.resolveApproval(chatId, d.path("id").asText(), !stop, v.isEmpty() ? null : v, false, false);
            return;
        }
        String writeDir = d.path("writeDir").asText("");
        ask("Allow? [y/N/a=allow for this chat"
                + (writeDir.isEmpty() ? "" : "/d=allow writes in " + writeDir + "/ and subfolders"
                + ", or 'd <path>' for another folder of the project") + "] ");
        String answer = in.take();
        String raw = answer.equals(EOF) ? "" : answer.strip();
        String a = raw.toLowerCase();
        boolean remember = a.startsWith("a");
        boolean rememberDir = !writeDir.isEmpty() && a.startsWith("d");
        String typedDir = rememberDir && raw.length() > 1 ? raw.substring(1).strip() : null;
        if (typedDir != null && typedDir.isEmpty()) typedDir = null;
        if (typedDir != null && org.kendar.llm.harness.permission.ApprovalService
                .grantDirOf(java.nio.file.Path.of("").toAbsolutePath(), typedDir) == null) {
            say("[warning] Path not allowed (absolute, '..' or .git): no folder granted");
        }
        boolean ok = remember || rememberDir || a.startsWith("y");
        harness.resolveApproval(chatId, d.path("id").asText(), ok, typedDir, remember, rememberDir);
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

    /** The model client for the command line, the name it is known by and the session-only entry, if any. */
    private record ProviderChoice(ModelClient client, String name, ProviderSettings session) {
    }

    /**
     * {@code --provider} picks a provider of the settings list (or a built-in). {@code --model}, {@code --base-url}
     * and {@code --api-key} override it for this session only; with an unknown provider name, or {@code --model}
     * alone, they define a {@code custom} entry that is never written to the settings file.
     */
    private static ProviderChoice chooseProvider(Map<String, String> a, HarnessSettings settings, ObjectMapper mapper,
                                                 int maxTokens) {
        ProviderCatalog catalog = new ProviderCatalog(settings.providers(), mapper, maxTokens);
        String name = a.containsKey("provider") ? a.get("provider")
                : settings.defaultProvider() != null ? settings.defaultProvider() : "anthropic";
        ProviderSettings base = catalog.find(name);
        boolean overrides = a.containsKey("model") || a.containsKey("base-url") || a.containsKey("api-key");
        if (base != null && !overrides) return new ProviderChoice(catalog.client(base), base.name(), null);
        if (base == null && !a.containsKey("base-url") && !a.containsKey("model")) {
            throw new IllegalArgumentException("Unknown provider '" + name + "' (known: "
                    + String.join(", ", catalog.names()) + "); give --base-url and --model to define a session entry");
        }
        ProviderSettings from = base != null ? base : new ProviderSettings(name, "openai", null, null, null,
                maxTokens, null, null, null, null, null, null, null);
        ProviderSettings merged = new ProviderSettings(from.name(), from.type(),
                a.getOrDefault("base-url", from.baseUrl()), a.getOrDefault("api-key", from.apiKey()),
                a.getOrDefault("model", from.model()), from.maxTokens() != null ? from.maxTokens() : maxTokens,
                from.maxRetries(), from.timeoutSeconds(), from.requireApiKey(), from.useMaxCompletionTokens(),
                from.contextTokens(), from.smallModel(), from.temperature(), null, from.thinking());
        catalog.setCustom(merged);
        return new ProviderChoice(catalog.client(ProviderCatalog.CUSTOM), ProviderCatalog.CUSTOM,
                catalog.find(ProviderCatalog.CUSTOM));
    }

    /** {@code --sandbox-clean[-all]}: removes the docker daemon-mode container(s), prints what went, and exits. */
    private static void cleanSandbox(HarnessConfig config, Path projectDir, boolean all) throws IOException {
        var runner = new org.kendar.llm.harness.sandbox.ProcessDockerCommandRunner();
        List<String> removed = all
                ? org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.cleanAllCli(config, runner)
                : org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.cleanStartupDir(config, runner, projectDir);
        if (removed.isEmpty()) System.out.println("No sandbox container to remove" + (all ? "." : " for " + projectDir));
        else removed.forEach(n -> System.out.println("removed " + n));
        System.out.println("Project files and .kendhar are untouched.");
    }

    /** A boolean flag: bare ({@code --small-model}) or with a value ({@code --small-model=false}). */
    private static boolean flag(Map<String, String> a, String name) {
        return a.containsKey(name) && !"false".equalsIgnoreCase(a.get(name));
    }

    private static double num(Map<String, String> a, String name) {
        return a.containsKey(name) ? Double.parseDouble(a.get(name)) : 0;
    }

    private static Map<String, String> parse(String[] argv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String k = argv[i];
            int eq = k.indexOf('=');
            if (k.startsWith("--") && eq > 2) { // --key=value, as the web application takes it
                m.put(k.substring(2, eq), k.substring(eq + 1));
                continue;
            }
            if (k.equals("-h") || k.equals("--help")) {
                m.put("help", "");
            } else if (k.equals("--small-model") || k.equals("--sandbox-clean") || k.equals("--sandbox-clean-all")
                    || k.equals("--docker-remote")) {
                m.put(k.substring(2), "");
            } else if (k.startsWith("--") && i + 1 < argv.length) {
                m.put(k.substring(2), argv[++i]);
            } else {
                throw new IllegalArgumentException("Unexpected argument " + k + " (use --help)");
            }
        }
        return m;
    }
}
