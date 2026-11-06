package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * java -cp ... org.kendar.llm.harness.Main [--provider anthropic|openai|qwen|llamacpp|ollama] [--model M]
 *     [--base-url U] [--api-key K] [--mode plan|edit|auto] [--workdir DIR] [--prompt TEXT]
 *     [--settings FILE] [--system-prompt TEXT] [--max-turns N] [--context-tokens TOKENS]
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

    private Main() {
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> a = parse(argv);
        if (a.containsKey("help")) {
            System.out.println("Usage: Main [--provider anthropic|openai|qwen|llamacpp|ollama] [--model M] [--base-url U] "
                    + "[--api-key K] [--mode plan|edit|auto] [--workdir DIR] [--prompt TEXT] "
                    + "[--system-prompt TEXT] [--max-turns N] [--context-tokens TOKENS] [--search-api-key BRAVE_KEY] "
                    + "[--settings FILE] [--context-strategy compaction|mnemonic|scope] [--max-tokens TOKENS] [--small-model] "
                    + "[--resume prompt|no|CHAT_ID] "
                    + "[--sandbox local|docker] [--docker-image IMAGE] [--docker-mode oneshot|daemon] "
                    + "[--docker-remote] [--docker-remote-host ADDRESS] [--sandbox-clean] [--sandbox-clean-all]");
            return;
        }
        if (ASK_TOKEN.equals(a.get("api-key"))) {
            String key = askToken();
            if (key.isEmpty()) throw new IllegalArgumentException("No API key given");
            a.put("api-key", key);
        }
        ObjectMapper mapper = new ObjectMapper();
        HarnessSettings settings = a.containsKey("settings") ? HarnessSettings.load(Path.of(a.get("settings")))
                : HarnessSettings.empty();
        // the flag wins over the settings file
        String tokens = a.containsKey("context-tokens") ? a.get("context-tokens") : a.get("context-budget");
        if (tokens != null) settings = settings.withMaxTokens(Integer.parseInt(tokens));
        if (a.containsKey("context-strategy")) settings = settings.withContextStrategy(a.get("context-strategy"));
        if (a.containsKey("small-model")) settings = settings.withSmallModel(true);
        if (a.containsKey("sandbox")) settings = settings.withSandboxBackend(a.get("sandbox"));
        if (a.containsKey("docker-image")) settings = settings.withDockerImage(a.get("docker-image"));
        if (a.containsKey("docker-mode")) settings = settings.withDockerMode(a.get("docker-mode"));
        if (a.containsKey("docker-remote") || a.containsKey("docker-remote-host")) {
            settings = settings.withDockerRemote(a.containsKey("docker-remote") ? Boolean.TRUE : null,
                    a.get("docker-remote-host"));
        }
        HarnessConfig config = settings.applyTo(HarnessConfig.defaults());
        // the agent works in the project directory; scratch directories go under the settings' sandbox.tempDir
        Path projectDir = Path.of(a.getOrDefault("workdir", "")).toAbsolutePath().normalize();
        String searchKey = a.getOrDefault("search-api-key", System.getenv("BRAVE_API_KEY"));
        if (searchKey != null) config = config.withSearchApiKey(searchKey);
        if (a.containsKey("max-turns")) config = config.withMaxTurns(Integer.parseInt(a.get("max-turns")));
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
        AgentHarness harness = AgentHarness.builder()
                .modelClient(choice.client())
                .activeProvider(choice.name())
                .sessionProvider(choice.session())
                .config(config)
                .systemPrompt(a.get("system-prompt"))
                .sandbox(sandbox)
                .sessionStore(new FileChatSessionStore(cwdSandbox.historyRoot(), mapper))
                .subAgentRunLog(new FileHistorySubAgentRunLog(new InMemorySubAgentRunLog(), cwdSandbox.historyRoot()))
                .mnemonicStore(new FileMnemonicStore(cwdSandbox.historyRoot()))
                .stateStore(new org.kendar.llm.harness.state.FileStateStore(cwdSandbox.historyRoot()))
                .settings(settings)
                .objectMapper(mapper)
                .discoverPlugins()
                .build();
        System.out.println("project dir: " + harness.sandbox().location() + (settings.dockerSandbox()
                ? " (shell commands run in docker image " + config.sandbox().docker().image()
                + (settings.dockerDaemon() ? ", long-running container, project mounted at /workspace)" : ")")
                : " (no isolation: shell commands run as you, plan mode cannot stop them writing here)"));
        System.out.println("scratch dir: " + cwdSandbox.scratchRoot());
        System.out.println("history dir: " + cwdSandbox.historyRoot());
        PermissionMode initialMode = PermissionMode.parse(a.get("mode"));
        BlockingQueue<AgentEvent> events = new LinkedBlockingQueue<>();
        Chat chat = new Chat();
        // piped input is the script, not an answer: ask only when nothing is waiting on stdin
        boolean interactive = !a.containsKey("prompt") && System.in.available() == 0;
        BlockingQueue<String> in = stdin();
        String resumeId = ResumeChooser.choose(harness, a.get("resume"), interactive, () -> {
            try {
                String l = in.take();
                return l == EOF ? null : l;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }, System.out);
        String openId = resumeId != null ? resumeId : harness.sessions().create(null).chatId();
        int deleted = harness.enforceChatRetention(settings.chatRetentionLimit(), openId);
        if (deleted > 0) System.out.println("chatRetentionLimit: deleted " + deleted + " old chat(s)");
        switchTo(harness, chat, openId, resumeId == null || a.containsKey("mode") ? initialMode : null, events);
        if (resumeId != null) {
            System.out.println("resumed chat " + openId);
            ResumeChooser.replay(harness, openId, System.out);
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
        System.out.println("agent-harness chat " + chat.id + " (type /help for commands, /exit to quit; /cancel stops a run)");
        while (true) {
            System.out.print(harness.sessions().get(chat.id).mode().toLowerCase() + "> ");
            System.out.flush();
            String line = in.take();
            if (line == EOF || line.strip().equals("/quit")
                    || line.strip().equals("/exit")) break;
            if (harness.commands().menuActive(chat.id)) {
                menu(harness.commands().menuInput(chat.id, line));
                continue;
            }
            if (line.isBlank()) continue;
            if (harness.commands().handles(line.strip())) {
                command(harness, chat, line.strip(), events);
                continue;
            }
            runTurn(harness, chat.id, line, events, in);
        }
        System.exit(0);
    }

    private static final String EOF = new String("<eof>");

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
        if (r.output() != null && !r.output().isBlank()) System.out.println(r.output());
    }

    private static void runTurn(AgentHarness harness, String chatId, String text, BlockingQueue<AgentEvent> events,
                                BlockingQueue<String> in) throws InterruptedException {
        events.clear();
        harness.start(chatId, text);
        while (true) {
            // Input typed during the run: a command (/cancel, /followup <text>) runs, any other line steers the agent
            for (String typed = in.poll(); typed != null; typed = in.poll()) {
                if (typed == EOF || typed.isBlank()) continue;
                if (typed.strip().equals("/cancel")) harness.cancel(chatId);
                else if (harness.commands().handles(typed.strip())) menu(harness.commands().execute(chatId, typed.strip()));
                else harness.steer(chatId, typed.strip());
            }
            AgentEvent ev = events.poll(100, TimeUnit.MILLISECONDS);
            if (ev == null) continue;
            JsonNode d = ev.data();
            switch (ev.type()) {
                case "text" -> System.out.println(d.path("text").asText());
                case "tool_call" -> System.out.println("[tool] " + d.path("name").asText() + " " + d.path("input"));
                case "tool_result" -> System.out.println("[result" + (d.path("isError").asBoolean() ? ", error" : "")
                        + "] " + abbreviate(d.path("output").asText()));
                case "warning" -> System.out.println("[warning] " + d.path("message").asText());
                case "error" -> System.err.println("[error] " + d.path("message").asText());
                case "compaction" -> System.out.println("[compaction] " + d);
                case "plan_ready" -> System.out.println("[plan] " + d.path("message").asText());
                case "approval_request" -> approve(harness, chatId, d, in);
                case "cancelled" -> System.out.println("[cancelled]");
                case "steer_queued" -> System.out.println("[queued " + d.path("kind").asText() + "] " + d.path("text").asText());
                case "steer_applied" -> System.out.println("[applied] " + d.path("text").asText());
                case "run_finished" -> {
                    return;
                }
                default -> { }
            }
        }
    }

    private static void approve(AgentHarness harness, String chatId, JsonNode d, BlockingQueue<String> in)
            throws InterruptedException {
        System.out.println("[approval] " + d.path("type").asText() + " " + d.path("title").asText());
        System.out.println(d.path("preview").asText());
        if ("continue".equals(d.path("type").asText())) {
            System.out.print("Turns to allow [" + d.path("proposed").asText() + ", n = stop] ");
            System.out.flush();
            String t = in.take();
            String v = t == EOF ? "n" : t.strip();
            boolean stop = v.toLowerCase().startsWith("n");
            harness.resolveApproval(chatId, d.path("id").asText(), !stop, v.isEmpty() ? null : v, false, false);
            return;
        }
        String writeDir = d.path("writeDir").asText("");
        System.out.print("Allow? [y/N/a=allow for this chat"
                + (writeDir.isEmpty() ? "" : "/d=allow writes in " + writeDir + "/ and subfolders") + "] ");
        System.out.flush();
        String answer = in.take();
        String a = answer == EOF ? "" : answer.strip().toLowerCase();
        boolean remember = a.startsWith("a");
        boolean rememberDir = !writeDir.isEmpty() && a.startsWith("d");
        boolean ok = remember || rememberDir || a.startsWith("y");
        harness.resolveApproval(chatId, d.path("id").asText(), ok, null, remember, rememberDir);
    }

    private static String abbreviate(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > 300 ? one.substring(0, 300) + "..." : one;
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
