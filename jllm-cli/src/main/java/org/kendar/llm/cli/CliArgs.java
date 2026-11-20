package org.kendar.llm.cli;

import java.util.HashMap;
import java.util.Map;

/** Command line parsing: {@code --key value}, {@code --key=value} and the bare boolean flags. */
final class CliArgs {

    static final String USAGE = "Usage: Main [--provider anthropic|openai|openaiGpt62|gemini|grok|qwen|llamacpp|ollama] [--model M] [--base-url U] "
            + "[--api-key K] [--mode plan|edit|auto] [--workdir DIR] [--prompt TEXT] "
            + "[--system-prompt TEXT] [--max-turns N] [--context-tokens TOKENS] [--search-api-key BRAVE_KEY] "
            + "[--settings FILE] [--context-strategy compaction|mnemonic|scope] [--max-tokens TOKENS] [--small-model] "
            + "[--resume prompt|no|CHAT_ID] "
            + "[--sandbox local|docker] [--docker-image IMAGE] [--docker-mode oneshot|daemon] "
            + "[--docker-remote] [--docker-remote-host ADDRESS] [--sandbox-clean] [--sandbox-clean-all] "
            + "[--pricing.input-per-million=USD] [--pricing.output-per-million=USD] [--pricing.cache-read-per-million=USD] [--pricing.cache-write-per-million=USD] [--plugin-dir DIR] [--plugins.ID.enabled=true|false] [--plugins.ID.settings.KEY=VALUE]";

    private CliArgs() {
    }

    /** A boolean flag: bare ({@code --small-model}) or with a value ({@code --small-model=false}). */
    static boolean flag(Map<String, String> a, String name) {
        return a.containsKey(name) && !"false".equalsIgnoreCase(a.get(name));
    }

    static double num(Map<String, String> a, String name) {
        return a.containsKey(name) ? Double.parseDouble(a.get(name)) : 0;
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
