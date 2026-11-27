package org.kendar.llm.cli;

import java.util.HashMap;
import java.util.Map;

/** Command line parsing: {@code --key value}, {@code --key=value} and the bare boolean flags. */
final class CliArgs {

    static final String USAGE = "Usage: Main [--provider anthropic|openai|openaiGpt62|gemini|grok|qwen|llamacpp|ollama] [--model M] [--base-url U] "
            + "[--api-key K] [--mode plan|edit|auto] [--workdir DIR] [--prompt TEXT] [--timeout SECONDS] [--json-result FILE] "
            + "[--system-prompt TEXT] [--max-turns N] [--context-tokens TOKENS] [--search-api-key BRAVE_KEY] "
            + "[--settings FILE] [--context-strategy compaction|mnemonic|scope] [--max-tokens TOKENS] [--small-model] "
            + "[--resume prompt|no|CHAT_ID] "
            + "[--sandbox local|docker|none] [--docker-image IMAGE] [--sandbox-scope chat|user] [--sandbox-profile standard|hardened] "
            + "[--docker-remote] [--docker-remote-host ADDRESS] [--sandbox-clean] [--sandbox-clean-all] "
            + "[--external-links none|read|readwrite] "
            + "[--pricing.input-per-million=USD] [--pricing.output-per-million=USD] [--pricing.cache-read-per-million=USD] [--pricing.cache-write-per-million=USD] [--plugin-dir DIR] [--plugins.ID.enabled=true|false] [--plugins.ID.settings.KEY=VALUE]"
            + "\n--sandbox none: no path jail (the tools reach any path), only with --mode auto; for a disposable container."
            + "\n--prompt exit codes: 0 completed; 1 failed or refused; 2 stuck; 3 max_turns, truncated or unverified;"
            + " 4 cancelled or --timeout; 64 usage error. --json-result FILE is written also on failure.";

    private CliArgs() {
    }

    /** A boolean flag: bare ({@code --small-model}) or with a value ({@code --small-model=false}). */
    static boolean flag(Map<String, String> a, String name) {
        return a.containsKey(name) && !"false".equalsIgnoreCase(a.get(name));
    }

    static double num(Map<String, String> a, String name) {
        return a.containsKey(name) ? Double.parseDouble(a.get(name)) : 0;
    }

    /**
     * {@code --timeout} in seconds, 0 when absent.
     *
     * @throws IllegalArgumentException when it is not a positive whole number
     */
    static long timeoutSeconds(Map<String, String> a) {
        if (!a.containsKey("timeout")) return 0;
        long t;
        try {
            t = Long.parseLong(a.get("timeout").strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--timeout needs a whole number of seconds: " + a.get("timeout"));
        }
        if (t <= 0) throw new IllegalArgumentException("--timeout needs a positive number of seconds: " + t);
        return t;
    }

    /**
     * Checks the flags of the one-shot run: {@code --timeout} and {@code --json-result} need {@code --prompt}.
     *
     * @throws IllegalArgumentException when they are given without it, or the timeout is not valid
     */
    static void checkHeadless(Map<String, String> a) {
        if (!a.containsKey("prompt") && (a.containsKey("timeout") || a.containsKey("json-result"))) {
            throw new IllegalArgumentException("--timeout and --json-result need --prompt");
        }
        timeoutSeconds(a);
    }

    /**
     * The value of {@code --name} read straight from the command line, also when it does not parse (to report a
     * usage error in the {@code --json-result} file); null when absent.
     */
    static String rawValue(String[] argv, String name) {
        String flag = "--" + name;
        String value = null;
        for (int i = 0; i < argv.length; i++) {
            if (argv[i].startsWith(flag + "=")) value = argv[i].substring(flag.length() + 1);
            else if (argv[i].equals(flag) && i + 1 < argv.length) value = argv[++i];
        }
        return value;
    }

    static Map<String, String> parse(String[] argv) {
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
