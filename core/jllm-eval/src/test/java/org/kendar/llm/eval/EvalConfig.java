package org.kendar.llm.eval;

import org.kendar.llm.eval.ModelEndpoint.Role;
import org.kendar.llm.harness.llm.ThinkingLevel;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/**
 * All jllm-eval settings. Resolution per key: system property (-D), environment variable, properties file; secret
 * keys ({@code *.apiKey}) never come from the file. {@link #load} reports every invalid key at once.
 */
public final class EvalConfig {

    public enum TargetKind {ANTHROPIC, REMOTE, LOCAL}

    private final Map<String, String> sys;
    private final Map<String, String> env;
    private final Properties file;
    private final List<String> errors = new ArrayList<>();
    private final Set<String> secretValues = new LinkedHashSet<>();
    private Secrets secrets = Secrets.NONE;

    private TargetKind targetKind;
    private String targetName;
    private ModelEndpoint target;
    private ModelEndpoint judge;
    private ModelEndpoint fast;
    private String judgeKind;
    private String fastKind;
    private String targetModelName;
    private String judgeModelName;
    private LocalSettings local;
    private int connectTimeoutSeconds;
    private int warmupTimeoutSeconds;
    private int trials;
    private int maxTrials;
    private int caseTimeoutSeconds;
    private boolean updateBaseline;
    private int compactionContextTokens;
    private String systemPromptExtra = "";
    private Path outputDir;
    private Path embeddingModelDir;
    private String embeddingSha256;

    private EvalConfig(Map<String, String> sys, Map<String, String> env, Properties file) {
        this.sys = sys;
        this.env = env;
        this.file = file;
    }

    public static EvalConfig load() {
        Properties p = new Properties();
        try (InputStream in = EvalConfig.class.getResourceAsStream("/eval.properties")) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            throw new EvalConfigException(List.of("cannot read eval.properties: " + e.getMessage()), Secrets.NONE);
        }
        Map<String, String> sys = new HashMap<>();
        System.getProperties().forEach((k, v) -> sys.put(String.valueOf(k), String.valueOf(v)));
        return load(p, sys, System.getenv());
    }

    public static EvalConfig load(Properties file, Map<String, String> sys, Map<String, String> env) {
        EvalConfig c = new EvalConfig(sys, env, file);
        c.resolve();
        c.secrets = new Secrets(c.secretValues);
        if (!c.errors.isEmpty()) throw new EvalConfigException(c.errors, c.secrets);
        return c;
    }

    // ---- accessors
    /** The secret values this configuration read: mask everything printed or written with them. */
    public Secrets secrets() { return secrets; }
    public TargetKind targetKind() { return targetKind; }
    public String targetName() { return targetName; }
    public ModelEndpoint target() { return target; }
    public ModelEndpoint judge() { return judge; }
    public ModelEndpoint fast() { return fast; }
    public String judgeKind() { return judgeKind; }
    public String fastKind() { return fastKind; }
    /**
     * Name of the target model in results, reports, baselines and history ({@code eval.target.modelName}); the
     * model id sent to the server when unset. Lets a server alias ({@code qwen-local}) be reported as the real model.
     */
    public String targetModelName() { return targetModelName; }
    /** Name of the judge model in results and reports ({@code eval.judge.modelName}); the model id when unset. */
    public String judgeModelName() { return judgeModelName; }
    public LocalSettings local() { return local; }
    public int connectTimeoutSeconds() { return connectTimeoutSeconds; }
    public int warmupTimeoutSeconds() { return warmupTimeoutSeconds; }
    public int trials() { return trials; }
    /** Upper bound on the trials of any case, including cases that set their own count. */
    public int maxTrials() { return maxTrials; }
    /** Upper bound on the seconds a user turn may take; 0 keeps each case's own timeout. */
    public int caseTimeoutSeconds() { return caseTimeoutSeconds; }
    /** Store this run as the model baseline once its report is written (after comparing with the old one). */
    public boolean updateBaseline() { return updateBaseline; }
    /** Text added to the system prompt of every case; empty (default) leaves the harness prompt as it is. */
    public String systemPromptExtra() { return systemPromptExtra; }
    /** Context size used for the cases tagged {@code compaction}; the other cases use the target's own. */
    public int compactionContextTokens() { return compactionContextTokens; }
    public Path outputDir() { return outputDir; }
    /** Folder holding the embedding model files; not required to exist (embedding tests skip when files are missing). */
    public Path embeddingModelDir() { return embeddingModelDir; }
    /** Expected SHA-256 of the embedding model file, lower case; null when unset (model verification then fails). */
    public String embeddingSha256() { return embeddingSha256; }

    /** True when judge and target are the same model on the same server. */
    public boolean selfGraded() {
        return judge.sameModelAs(target);
    }

    /** True when the fast model is a different endpoint than the target. */
    public boolean fastSeparate() {
        return !fast.sameModelAs(target);
    }

    // ---- raw lookup
    static String envName(String key) {
        return key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static boolean isSecret(String key) {
        return key.endsWith(".apiKey");
    }

    private String raw(String key) {
        String v = sys.get(key);
        if (v == null || v.isBlank()) v = env.get(envName(key));
        if ((v == null || v.isBlank()) && !isSecret(key)) v = file.getProperty(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** First non-blank of the key and the fallbacks (each a property key or a plain environment variable name). */
    private String secret(String key, String... fallbacks) {
        String v = raw(key);
        for (String f : fallbacks) {
            if (v != null) break;
            v = f.contains(".") ? raw(f) : env.get(f);
            if (v != null && v.isBlank()) v = null;
        }
        if (v != null) secretValues.add(v);
        return v;
    }

    private String str(String key, boolean required) {
        String v = raw(key);
        if (v == null && required) errors.add(key + ": missing");
        return v;
    }

    private int integerOr(String key, int min, int def) {
        return raw(key) == null ? def : integer(key, min);
    }

    private int integer(String key, int min) {
        String v = str(key, true);
        if (v == null) return min;
        try {
            int n = Integer.parseInt(v);
            if (n < min) errors.add(key + ": must be >= " + min + " (got " + n + ")");
            return n;
        } catch (NumberFormatException e) {
            errors.add(key + ": not an integer ('" + v + "')");
            return min;
        }
    }

    private boolean bool(String key, boolean def) {
        String v = raw(key);
        if (v == null) return def;
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        errors.add(key + ": must be true or false (got '" + v + "')");
        return def;
    }

    private String url(String key) {
        String v = str(key, true);
        if (v == null) return "";
        try {
            URI u = URI.create(v);
            if (u.getHost() == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) {
                errors.add(key + ": not an http(s) address ('" + v + "')");
            }
        } catch (IllegalArgumentException e) {
            errors.add(key + ": not a valid address ('" + v + "')");
        }
        return v;
    }

    private void rejectSecretsInFile() {
        for (String k : file.stringPropertyNames()) {
            if (isSecret(k) && !file.getProperty(k).isBlank()) {
                errors.add(k + ": secrets must not be in eval.properties; use the environment or -D");
            }
        }
    }

    // ---- resolution
    private void resolve() {
        rejectSecretsInFile();
        connectTimeoutSeconds = integer("eval.timeout.connect", 1);
        warmupTimeoutSeconds = integer("eval.timeout.warmup", 1);
        trials = integer("eval.trials", 1);
        maxTrials = integerOr("eval.maxTrials", 1, 10);
        caseTimeoutSeconds = integerOr("eval.caseTimeoutSeconds", 0, 0);
        updateBaseline = bool("eval.updateBaseline", false);
        String extra = str("eval.systemPrompt.extra", false);
        systemPromptExtra = extra == null ? "" : extra.strip();
        compactionContextTokens = integerOr("eval.compaction.contextTokens", 1024, 65536);
        String out = str("eval.outputDir", true);
        outputDir = Path.of(out == null ? "eval-results" : out);

        resolveEmbedding();
        resolveTarget();
        resolveJudge();
        resolveFast();
        targetModelName = modelName("eval.target.modelName", target);
        judgeModelName = modelName("eval.judge.modelName", judge);
    }

    /** The override, else the endpoint's model id; blank when there is no endpoint (already an error). */
    private String modelName(String key, ModelEndpoint endpoint) {
        String v = str(key, false);
        if (v != null) return v;
        return endpoint == null ? "" : endpoint.model();
    }

    private void resolveEmbedding() {
        String dir = str("eval.embedding.modelDir", true);
        if (dir != null) {
            try {
                embeddingModelDir = Path.of(dir);
                if (java.nio.file.Files.exists(embeddingModelDir) && !java.nio.file.Files.isDirectory(embeddingModelDir)) {
                    errors.add("eval.embedding.modelDir: not a directory ('" + dir + "')");
                }
            } catch (java.nio.file.InvalidPathException e) {
                errors.add("eval.embedding.modelDir: not a valid path ('" + dir + "')");
            }
        }
        String sha = raw("eval.embedding.sha256");
        if (sha != null) {
            if (sha.matches("(?i)[0-9a-f]{64}")) embeddingSha256 = sha.toLowerCase(Locale.ROOT);
            else errors.add("eval.embedding.sha256: must be 64 hex characters (got '" + sha + "')");
        }
    }

    private void resolveTarget() {
        String t = str("eval.target", true);
        if (t == null) return;
        if (t.equals("anthropic")) {
            targetKind = TargetKind.ANTHROPIC;
            targetName = "anthropic";
            target = anthropic(Role.TARGET, "eval.anthropic", "eval.anthropic.model");
        } else if (t.startsWith("remote.") && t.length() > 7) {
            targetKind = TargetKind.REMOTE;
            targetName = t.substring(7);
            target = remote(Role.TARGET, targetName);
        } else if (t.startsWith("local.") && t.length() > 6) {
            targetKind = TargetKind.LOCAL;
            targetName = t.substring(6);
            local = local(targetName);
            if (local != null) {
                LocalSettings.Model m = local.model();
                target = new ModelEndpoint(Role.TARGET, "local-" + m.key(), ModelEndpoint.OPENAI, local.baseUrl(),
                        m.alias(), m.contextTokens(), m.smallModel(), 1800, null);
            }
        } else {
            errors.add("eval.target: must be 'anthropic', 'remote.<name>' or 'local.<strong|alt|small>' (got '" + t + "')");
        }
    }

    private ModelEndpoint anthropic(Role role, String prefix, String modelKey) {
        String model = str(modelKey, true);
        String base = url("eval.anthropic.baseUrl");
        int ctx = integer("eval.anthropic.contextTokens", 1024);
        int timeout = integer("eval.anthropic.timeoutSeconds", 1);
        String key = secret(prefix + ".apiKey", "eval.anthropic.apiKey", "ANTHROPIC_API_KEY");
        if (key == null) {
            errors.add(prefix + ".apiKey: missing (set EVAL_ANTHROPIC_APIKEY or ANTHROPIC_API_KEY, or pass -Deval.anthropic.apiKey)");
        }
        int maxRpm = integerOr("eval.anthropic.maxRpm", 0, 0);
        // minimal|low|medium|high; on current models it is sent as output_config.effort
        return new ModelEndpoint(role, "anthropic", ModelEndpoint.ANTHROPIC, base, model == null ? "" : model, ctx,
                false, timeout, key, thinking(prefix + ".thinking"), maxRpm);
    }

    private ModelEndpoint remote(Role role, String name) {
        String p = "eval.remote." + name;
        boolean exists = file.stringPropertyNames().stream().anyMatch(k -> k.startsWith(p + "."))
                || sys.keySet().stream().anyMatch(k -> k.startsWith(p + "."));
        if (!exists) {
            errors.add(p + ".*: no such remote entry '" + name + "'");
            return null;
        }
        String proto = str(p + ".protocol", true);
        if (proto != null && !proto.equals("openai") && !proto.equals("anthropic")) {
            errors.add(p + ".protocol: must be 'openai' or 'anthropic' (got '" + proto + "')");
        }
        String base = url(p + ".baseUrl");
        String model = str(p + ".model", true);
        int ctx = integer(p + ".contextTokens", 1024);
        boolean small = bool(p + ".smallModel", false);
        int timeout = integer(p + ".timeoutSeconds", 1);
        String key = secret(p + ".apiKey");
        int maxRpm = integerOr(p + ".maxRpm", 0, 0);
        return new ModelEndpoint(role, name, proto == null ? "openai" : proto, base, model == null ? "" : model, ctx,
                small, timeout, key, thinking(p + ".thinking"), maxRpm);
    }

    private ThinkingLevel thinking(String key) {
        String v = str(key, false);
        if (v == null) return null;
        try {
            return ThinkingLevel.parse(v);
        } catch (IllegalArgumentException e) {
            errors.add(key + ": " + e.getMessage());
            return null;
        }
    }

    private LocalSettings local(String modelKey) {
        if (!List.of("strong", "alt", "small").contains(modelKey)) {
            errors.add("eval.target: unknown local model '" + modelKey + "' (strong | alt | small)");
            return null;
        }
        int port = integer("eval.local.port", 1);
        String p = "eval.local.model." + modelKey;
        String f = str(p + ".file", true);
        String alias = str(p + ".alias", true);
        int ctx = integer(p + ".contextTokens", 1024);
        String maxCtx = raw(p + ".maxContextTokens");
        if (maxCtx != null) {
            try {
                if (ctx > Integer.parseInt(maxCtx)) {
                    errors.add(p + ".contextTokens: exceeds " + p + ".maxContextTokens (" + maxCtx + ")");
                }
            } catch (NumberFormatException e) {
                errors.add(p + ".maxContextTokens: not an integer ('" + maxCtx + "')");
            }
        }
        boolean small = bool(p + ".smallModel", false);
        String extra = raw(p + ".extraArgs");
        if (alias != null && alias.matches("(?i).*[-_.](i?q\\d(_[a-z0-9]+)*|mxfp4|f16|bf16)$")) {
            errors.add(p + ".alias: must not carry the quantization (got '" + alias + "')");
        }
        return new LocalSettings(port,
                new LocalSettings.Model(modelKey, f == null ? "" : f, alias == null ? "" : alias, ctx, small, extra));
    }

    private void resolveJudge() {
        judgeKind = str("eval.judge", true);
        if (judgeKind == null) return;
        switch (judgeKind) {
            case "anthropic" -> judge = anthropic(Role.JUDGE, "eval.judge.anthropic", "eval.judge.anthropic.model");
            case "openai" -> {
                String p = "eval.judge.openai";
                String base = url(p + ".baseUrl");
                String model = str(p + ".model", true);
                int ctx = integer(p + ".contextTokens", 1024);
                int timeout = integer(p + ".timeoutSeconds", 1);
                String name = str(p + ".name", false);
                judge = new ModelEndpoint(Role.JUDGE, name == null ? "judge" : name, ModelEndpoint.OPENAI, base,
                        model == null ? "" : model, ctx, false, timeout, secret(p + ".apiKey"), thinking(p + ".thinking"),
                        integerOr(p + ".maxRpm", 0, 0));
            }
            default -> errors.add("eval.judge: must be 'anthropic' or 'openai' (got '" + judgeKind + "')");
        }
    }

    private void resolveFast() {
        fastKind = str("eval.fast", true);
        if (fastKind == null) return;
        if (fastKind.equals("target")) {
            fast = target == null ? null : target.withRole(Role.FAST);
        } else if (fastKind.equals("anthropic")) {
            fast = anthropic(Role.FAST, "eval.fast.anthropic", "eval.fast.anthropic.model");
        } else if (fastKind.startsWith("remote.") && fastKind.length() > 7) {
            fast = remote(Role.FAST, fastKind.substring(7));
        } else {
            errors.add("eval.fast: must be 'target', 'anthropic' or 'remote.<name>' (got '" + fastKind + "')");
        }
    }
}
