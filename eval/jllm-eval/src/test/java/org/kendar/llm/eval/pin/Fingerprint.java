package org.kendar.llm.eval.pin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.eval.EvalConfig;
import org.kendar.llm.eval.Secrets;
import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.run.TrialRunner;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.Prompts;
import org.kendar.llm.harness.context.ContextStrategyKind;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Hashes that pin what a result was measured with: the settings, the harness prompts and each case. All of them go
 * through canonical JSON (keys sorted at every level), so the order the settings were given in never matters.
 * Secrets are never part of any input.
 */
public final class Fingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String PROMPTS = "/agent-harness/prompts/";
    private static final String WORKSPACE = "<workspace>";

    private Fingerprint() {
    }

    /**
     * @param hash          SHA-256 of {@code canonicalJson}
     * @param canonicalJson the hashed text: sorted keys, no secrets (the values of the API keys are not in it at all)
     */
    public record Settings(String hash, String canonicalJson) {

        /** Saves the canonical JSON as {@code settings.json} in {@code dir}, with every secret masked. */
        public Path write(Path dir, Secrets secrets) throws IOException {
            Files.createDirectories(dir);
            Path file = dir.resolve("settings.json");
            Files.writeString(file, secrets.maskJson(canonicalJson), StandardCharsets.UTF_8);
            return file;
        }
    }

    /**
     * The fingerprint of the settings: the resolved eval keys, the trial settings and the harness configuration the
     * trials start from.
     */
    public static Settings settings(EvalConfig c) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode eval = root.putObject("eval");
        c.resolvedKeys().forEach(eval::put);
        ObjectNode trial = root.putObject("trial");
        trial.put("trials", c.trials());
        trial.put("maxTrials", c.maxTrials());
        trial.put("passK", c.passK().toString());
        trial.put("caseTimeoutSeconds", c.caseTimeoutSeconds());
        trial.put("contextTokens", c.target().contextTokens());
        trial.put("compactionContextTokens", c.compactionContextTokens());
        trial.put("smallModel", c.target().smallModel());
        trial.put("systemPromptExtra", c.systemPromptExtra());
        root.set("harness", configNode(TrialRunner.trialBase().withSandboxBaseDir(WORKSPACE)));
        String json = write(canonical(root));
        return new Settings(sha256(json), json);
    }

    /** SHA-256 over the harness prompt resources, by file name, with line endings normalised. */
    public static String prompts() {
        URL dir = Prompts.class.getResource(PROMPTS);
        if (dir == null) return BuildInfo.UNKNOWN;
        try {
            Map<String, String> files = new TreeMap<>();
            if ("jar".equals(dir.getProtocol())) {
                JarURLConnection conn = (JarURLConnection) dir.openConnection();
                conn.setUseCaches(false);
                try (JarFile jar = conn.getJarFile()) {
                    String prefix = PROMPTS.substring(1);
                    for (Iterator<JarEntry> it = jar.entries().asIterator(); it.hasNext(); ) {
                        JarEntry e = it.next();
                        if (e.isDirectory() || !e.getName().startsWith(prefix)) continue;
                        try (InputStream in = jar.getInputStream(e)) {
                            files.put(e.getName().substring(prefix.length()), text(in.readAllBytes()));
                        }
                    }
                }
            } else {
                Path folder = Path.of(dir.toURI());
                try (Stream<Path> s = Files.list(folder)) {
                    for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                        files.put(f.getFileName().toString(), text(Files.readAllBytes(f)));
                    }
                }
            }
            if (files.isEmpty()) return BuildInfo.UNKNOWN;
            StringBuilder sb = new StringBuilder();
            files.forEach((n, t) -> sb.append(n).append('\n').append(t).append('\0'));
            return sha256(sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Hash of a case under a strategy: its description (turns, check names, thresholds, budgets) plus the harness
     * configuration after the case's own customization. What the case's harness builder callback or scripted model do
     * is code and cannot be hashed; a change there shows in the commit and the diff hash instead.
     */
    public static String caseHash(EvalCase c, ContextStrategyKind strategy) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("name", c.name());
        n.put("feature", c.feature());
        n.put("tag", c.tag().name());
        n.put("strategy", strategy.name());
        ArrayNode steps = n.putArray("steps");
        for (EvalCase.Step s : c.steps()) {
            if (s instanceof EvalCase.UserTurn u) steps.addObject().put("user", u.text());
            else if (s instanceof EvalCase.Action a) steps.addObject().put("action", a.label());
        }
        ArrayNode checks = n.putArray("checks");
        c.checks().forEach(k -> checks.addObject().put("name", k.name()).put("tier", k.tier().name()));
        ArrayNode strategies = n.putArray("strategies");
        c.strategies().forEach(k -> strategies.add(k.name()));
        if (c.trials() != null) n.put("trials", c.trials());
        n.put("risk", c.risk());
        n.put("minPassRate", c.minPassRate());
        if (c.rubric() != null) n.put("rubric", c.rubric());
        n.put("maxTurns", c.maxTurns());
        n.put("timeoutSeconds", c.timeoutSeconds());
        Integer budget = c.contextBudgetTokens(strategy);
        if (budget != null) n.put("contextBudgetTokens", budget);
        if (c.mode() != null) n.put("mode", c.mode().name());
        n.put("scriptedModel", c.model() != null);
        HarnessConfig config = c.config().apply(TrialRunner.caseConfig(c, strategy, budget == null ? -1 : budget, WORKSPACE));
        n.set("config", configNode(config));
        return sha256(write(canonical(n)));
    }

    private static JsonNode configNode(HarnessConfig config) {
        JsonNode node = MAPPER.valueToTree(config);
        if (node.path("web") instanceof ObjectNode web) web.remove("searchApiKey");
        // a set has no order of its own
        if (node instanceof ObjectNode o && o.path("coreGroups") instanceof ArrayNode groups) {
            List<String> sorted = new ArrayList<>();
            groups.forEach(g -> sorted.add(g.asText()));
            Collections.sort(sorted);
            ArrayNode a = o.putArray("coreGroups");
            sorted.forEach(a::add);
        }
        return node;
    }

    /** A copy with the keys of every object sorted; array order is kept. */
    public static JsonNode canonical(JsonNode node) {
        if (node instanceof ObjectNode o) {
            ObjectNode out = MAPPER.createObjectNode();
            Map<String, JsonNode> sorted = new TreeMap<>();
            o.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            sorted.forEach((k, v) -> out.set(k, canonical(v)));
            return out;
        }
        if (node instanceof ArrayNode a) {
            ArrayNode out = MAPPER.createArrayNode();
            a.forEach(v -> out.add(canonical(v)));
            return out;
        }
        return node;
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
