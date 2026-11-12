package org.kendar.llm.js;

import org.graalvm.polyglot.Source;
import org.kendar.llm.harness.tools.HarnessTool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the JavaScript module that exposes the tools of one agent as {@code tools.<name>(args)}, and caches the
 * parsed {@link Source} by the list of tool names and schemas (LRU, a few dozen entries).
 */
final class ToolLibrary {

    private static final int CACHE_SIZE = 32;

    private final Map<String, Source> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Source> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /** The JavaScript name of a tool: {@code -} becomes {@code _}. */
    static String jsName(String toolName) {
        return toolName.replace('-', '_');
    }

    /**
     * Rejects tool names that collide after the mapping ({@code a-b} and {@code a_b}).
     *
     * @throws IllegalArgumentException naming both tools
     */
    static void checkNames(Collection<String> names) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String n : names) {
            String prev = seen.putIfAbsent(jsName(n), n);
            if (prev != null && !prev.equals(n)) {
                throw new IllegalArgumentException("Tools '" + prev + "' and '" + n + "' have the same name in the "
                        + "run_js library (tools." + jsName(n) + "); rename or disable one of them");
            }
        }
    }

    /** The library of the given tools; the same list gives the same cached {@link Source}. */
    synchronized Source source(Collection<HarnessTool> tools) {
        List<HarnessTool> sorted = new ArrayList<>(tools);
        sorted.sort(java.util.Comparator.comparing(HarnessTool::name));
        String text = generate(sorted);
        StringBuilder schemas = new StringBuilder(text);
        sorted.forEach(t -> schemas.append(t.inputSchema()));
        String key = sha256(schemas.toString());
        return cache.computeIfAbsent(key, k -> Source.create("js", text));
    }

    synchronized int cacheSize() {
        return cache.size();
    }

    /** Deterministic: sorted by name, no timestamps. */
    static String generate(List<HarnessTool> sorted) {
        StringBuilder sb = new StringBuilder();
        sb.append("(function () {\n");
        sb.append("  const call = globalThis.__invoke;\n");
        sb.append("  delete globalThis.__invoke;\n");
        sb.append("  const run = (name, args) => {\n");
        sb.append("    const r = JSON.parse(call(name, JSON.stringify(args === undefined || args === null ? {} : args)));\n");
        sb.append("    if (r.error !== undefined) throw new Error(r.error);\n");
        sb.append("    try { return JSON.parse(r.value); } catch (e) { return r.value; }\n");
        sb.append("  };\n");
        sb.append("  const tools = {\n");
        for (HarnessTool t : sorted) {
            sb.append("    /**\n");
            for (String line : String.valueOf(t.description()).split("\\R")) {
                sb.append("     * ").append(line.replace("*/", "* /")).append('\n');
            }
            sb.append("     * @group ").append(t.group()).append("\n");
            sb.append("     * @kind ").append(t.kind()).append("\n");
            sb.append("     */\n");
            sb.append("    ").append(quote(jsName(t.name()))).append(": (args) => run(").append(quote(t.name()))
                    .append(", args),\n");
        }
        sb.append("  };\n");
        sb.append("  Object.defineProperty(globalThis, 'tools', "
                + "{ value: Object.freeze(tools), writable: false, configurable: false, enumerable: true });\n");
        sb.append("})();\n");
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
