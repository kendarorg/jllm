package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.kendar.llm.harness.safety.ContentScanner;
import org.kendar.llm.harness.safety.ContentWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads {@link HarnessSettings} from files: a single file, or the merged layers of a {@link SettingsSource}. */
public final class HarnessSettingsLoader {

    private static final Logger LOG = LoggerFactory.getLogger(HarnessSettingsLoader.class);
    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private HarnessSettingsLoader() {
    }

    public static HarnessSettings load(Path file) {
        return load(file, System::getenv);
    }

    /** @throws IllegalStateException if a referenced environment variable is not set */
    public static HarnessSettings load(Path file, Function<String, String> env) {
        ObjectMapper mapper = mapper(LOG::warn);
        try {
            JsonNode tree = expand(mapper.readTree(file.toFile()), env);
            return mapper.treeToValue(tree, HarnessSettings.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read settings " + file, e);
        }
    }

    /** The global settings file {@code <jllm.home or user.home>/.jllm/settings.json}. */
    public static Path globalFile() {
        return Path.of(System.getProperty("jllm.home", System.getProperty("user.home")), ".jllm", "settings.json");
    }

    /** The project settings file {@code <projectDir>/.jllm/settings.json}. */
    public static Path projectFile(Path projectDir) {
        return projectDir.resolve(".jllm").resolve("settings.json");
    }

    /**
     * The layers of {@code source} merged: global, then project, then explicit, then stored ({@link SettingsSink}); later wins, objects merged per key,
     * other values (including arrays) replaced, except {@code permissions.deny}: the union of every layer (a later
     * layer can only add denials). A layer whose root is not a JSON object is ignored with a warning. Stdio MCP
     * servers, stop hooks and OAuth blocks that only the project layer defines count only when trusted, see
     * {@link ProjectTrustFilter}; the project layer cannot set {@code tokenStore} nor name a literal client secret.
     *
     * @param projectDir the project the {@link SettingsLayer.Kind#PROJECT} layer belongs to (the key of the trust
     *                   answers); null when there is no project layer
     * @param prompt     asks about what the project defines and was not answered before; null = no one to ask
     * @param trust      where the answers are remembered
     * @param contents   gets what the {@link ContentScanner} finds in the free text of
     *                   each layer (descriptions, prompts, see {@code ContentScanner.scanSettings}); the layer is
     *                   loaded unchanged whatever is found
     */
    public static HarnessSettings loadLayered(SettingsSource source, Path projectDir, Function<String, String> env,
                                              FolderTrust.Prompt prompt, FolderTrust trust,
                                              Consumer<String> warn, ContentWarnings contents) {
        ObjectMapper mapper = mapper(warn);
        JsonNode merged = null;
        List<JsonNode> deny = new ArrayList<>();
        JsonNode projectTree = null;
        List<JsonNode> otherTrees = new ArrayList<>();
        try {
            for (SettingsLayer layer : source.layers()) {
                JsonNode tree = layer.tree();
                if (!(tree instanceof ObjectNode)) {
                    warn.accept("Settings " + layer.origin() + " ignored: its root must be a JSON object, not "
                            + (tree == null || tree.isMissingNode() ? "empty" : tree.getNodeType().name().toLowerCase()));
                    continue;
                }
                contents.report(layer.origin(), ContentScanner.scanSettings(layer.origin(), tree));
                tree = tree.deepCopy();
                for (JsonNode d : tree.path("permissions").path("deny")) {
                    if (!deny.contains(d)) deny.add(d.deepCopy());
                }
                if (layer.kind() == SettingsLayer.Kind.PROJECT) projectTree = tree.deepCopy();
                else otherTrees.add(tree.deepCopy());
                merged = merged == null ? tree : merge(merged, tree);
            }
            if (merged == null) return HarnessSettings.empty();
            if (projectTree != null && projectDir == null) {
                throw new IllegalArgumentException("A project settings layer needs its project directory");
            }
            if (!deny.isEmpty()) {
                ObjectNode perms = merged.get("permissions") instanceof ObjectNode o ? o
                        : ((ObjectNode) merged).putObject("permissions");
                ArrayNode union = perms.putArray("deny");
                deny.forEach(union::add);
            }
            if (projectTree != null) {
                ProjectTrustFilter.apply((ObjectNode) merged, projectTree, otherTrees, projectDir,
                        prompt == null ? FolderTrust.Prompt.NONE : prompt, trust, warn);
            }
            return mapper.treeToValue(expand(merged, env), HarnessSettings.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read settings", e);
        }
    }

    /**
     * A lenient settings mapper: an unknown property is skipped and reported to {@code warn} as
     * {@code Unknown setting '<name>' at <json-pointer> (ignored)}.
     */
    static ObjectMapper mapper(Consumer<String> warn) {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.addHandler(new com.fasterxml.jackson.databind.deser.DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(com.fasterxml.jackson.databind.DeserializationContext ctxt,
                                                 com.fasterxml.jackson.core.JsonParser p,
                                                 com.fasterxml.jackson.databind.JsonDeserializer<?> deserializer,
                                                 Object beanOrClass, String propertyName) throws IOException {
                String at;
                try {
                    at = p.getParsingContext().pathAsPointer().toString();
                } catch (RuntimeException e) { // boundary: diagnostics only
                    at = "?";
                }
                warn.accept("Unknown setting '" + propertyName + "' at " + (at.isEmpty() ? "/" : at) + " (ignored)");
                p.skipChildren();
                return true;
            }
        });
        return mapper;
    }

    static JsonNode merge(JsonNode base, JsonNode over) {
        if (base instanceof ObjectNode b && over instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode existing = b.get(e.getKey());
                b.set(e.getKey(), existing == null ? e.getValue() : merge(existing, e.getValue()));
            }
            return b;
        }
        return over;
    }

    static JsonNode expand(JsonNode node, Function<String, String> env) {
        if (node.isTextual()) return TextNode.valueOf(expand(node.asText(), env));
        if (node instanceof ObjectNode obj) {
            for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                e.setValue(expand(e.getValue(), env));
            }
        } else if (node instanceof ArrayNode arr) {
            List<JsonNode> items = new ArrayList<>();
            arr.forEach(i -> items.add(expand(i, env)));
            arr.removeAll();
            arr.addAll(items);
        }
        return node;
    }

    static String expand(String text, Function<String, String> env) {
        Matcher m = ENV.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = env.apply(m.group(1));
            if (v == null) throw new IllegalStateException("Environment variable " + m.group(1) + " is not set");
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }
}
