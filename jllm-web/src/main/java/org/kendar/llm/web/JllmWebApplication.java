package org.kendar.llm.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JllmWebApplication {
    static final String ASK_TOKEN = "ASK_TOKEN";
    static final int TOKEN_WAIT_SECONDS = 30;
    private static final Set<String> KEYLESS_PROVIDERS = Set.of("llamacpp", "ollama");

    /** The flags the CLI takes too, spelled the same (CLI-only: --mode, --prompt, --sandbox-clean, --sandbox-clean-all). */
    static final String USAGE = "Usage: jllm-web [--provider anthropic|openai|qwen|llamacpp|ollama] [--model M] "
            + "[--base-url U] [--api-key K] [--workdir DIR] [--system-prompt TEXT] [--max-turns N] "
            + "[--context-tokens TOKENS] [--search-api-key BRAVE_KEY] [--settings FILE] "
            + "[--context-strategy compaction|mnemonic] [--max-tokens TOKENS] [--small-model] "
            + "[--sandbox local|docker] [--docker-image IMAGE] [--docker-mode oneshot|daemon] "
            + "[--docker-remote] [--docker-remote-host ADDRESS]"
            + System.lineSeparator() + "Flags take --flag value or --flag=value. Spring's own flags (--server.port=9000) work too.";

    public static void main(String[] args) {
        args = normalize(args);
        for (String a : args) {
            if (a.equals("-h") || a.equals("--help")) {
                System.out.println(USAGE);
                return;
            }
        }
        waitForApiKey(args);
        SpringApplication.run(JllmWebApplication.class, args);
    }

    /**
     * Before Spring starts: if no API key is available (command line, system property, env), waits up to
     * {@value #TOKEN_WAIT_SECONDS}s for it to be typed on stdin, then publishes it as {@code jllm.api-key}.
     */
    private static void waitForApiKey(String[] args) {
        String provider = arg(args, "jllm.provider");
        if (provider == null) provider = System.getProperty("jllm.provider");
        if (provider == null) provider = System.getenv("JLLM_PROVIDER");
        if (provider == null) provider = "anthropic";
        provider = provider.toLowerCase(Locale.ROOT);
        if (KEYLESS_PROVIDERS.contains(provider)) return;

        String key = arg(args, "jllm.api-key");
        if (key == null) key = System.getProperty("jllm.api-key");
        if (key == null) key = System.getenv("JLLM_API_KEY");
        if (key == null) key = System.getenv(provider.toUpperCase(Locale.ROOT) + "_API_KEY");
        if (key != null && !key.isBlank() && !ASK_TOKEN.equals(key)) return;

        System.out.println("No API key for provider '" + provider + "'. Enter it within "
                + TOKEN_WAIT_SECONDS + "s (or set " + provider.toUpperCase(Locale.ROOT) + "_API_KEY):");
        CompletableFuture<String> input = CompletableFuture.supplyAsync(() -> {
            try {
                return new BufferedReader(new InputStreamReader(System.in)).readLine();
            } catch (Exception e) {
                return null;
            }
        });
        try {
            String typed = input.get(TOKEN_WAIT_SECONDS, TimeUnit.SECONDS);
            if (typed != null && !typed.isBlank()) {
                System.setProperty("jllm.api-key", typed.trim());
                return;
            }
        } catch (Exception ignored) {
            // timeout or interrupted
        }
        System.out.println("No API key provided; starting without it.");
    }

    /**
     * Accepts the same short flags as the CLI: {@code --sandbox docker}, {@code --sandbox=docker}, {@code --small-model}
     * become {@code --jllm.sandbox=docker}, {@code --jllm.small-model=true}. Anything else, including the long
     * Spring's own flags are left alone.
     */
    static String[] normalize(String[] args) {
        Set<String> known = new java.util.HashSet<>();
        for (var c : JllmProperties.class.getRecordComponents()) {
            if (!c.getName().equals("pricing")) known.add(c.getName().replaceAll("([A-Z])", "-$1").toLowerCase(Locale.ROOT));
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                out.add(a);
                continue;
            }
            int eq = a.indexOf('=');
            String key = a.substring(2, eq < 0 ? a.length() : eq);
            if (key.equals("settings")) key = "settings-file"; // same flag as the CLI
            if (!known.contains(key)) {
                out.add(a);
            } else if (eq >= 0) {
                out.add("--jllm." + key + a.substring(eq));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.add("--jllm." + key + "=" + args[++i]);
            } else {
                out.add("--jllm." + key + "=true"); // flag without a value, e.g. --small-model
            }
        }
        return out.toArray(String[]::new);
    }

    private static String arg(String[] args, String name) {
        String prefix = "--" + name + "=";
        for (String a : args) if (a.startsWith(prefix)) return a.substring(prefix.length());
        return null;
    }
}
