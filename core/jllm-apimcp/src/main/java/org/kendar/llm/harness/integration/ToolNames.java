package org.kendar.llm.harness.integration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool names must match {@code [a-zA-Z0-9_-]{1,64}} to be accepted by the model APIs. Sanitizing can make two
 * different tools share a name ({@code a.b} and {@code a_b}, or the same first 64 characters): {@link Claims} detects
 * it so that the clash fails loudly instead of one tool silently replacing the other.
 */
public final class ToolNames {

    private ToolNames() {
    }

    public static String of(String raw) {
        String n = raw.replaceAll("[^a-zA-Z0-9_-]", "_");
        return n.length() <= 64 ? n : n.substring(0, 64);
    }

    /** Two tools ended up with the same name; the message names both. */
    public static final class CollisionException extends IllegalStateException {
        private final String toolName;
        private final String first;
        private final String second;

        public CollisionException(String toolName, String first, String second) {
            super("Tool name collision: '" + toolName + "' is the name of both " + first + " and " + second
                    + "; rename or filter out one of them (allowList/denyList, allowedPaths/denyPaths)");
            this.toolName = toolName;
            this.first = first;
            this.second = second;
        }

        public String toolName() {
            return toolName;
        }

        /** The tool that held the name first. */
        public String first() {
            return first;
        }

        /** The tool that asked for the name again. */
        public String second() {
            return second;
        }
    }

    /**
     * The tool names taken by the sources of a harness, each with a human description of its original tool (e.g.
     * {@code MCP server 'srv' tool 'a.b'}). Thread-safe.
     */
    public static final class Claims {
        /** name -> (source, origin) */
        private final Map<String, String[]> owners = new HashMap<>();

        /**
         * Takes the names of one source, replacing what that source held before. Nothing is taken when one of the
         * names belongs to another source.
         *
         * @param names tool name -> description of the original tool
         * @throws CollisionException naming both tools
         */
        public synchronized void claimAll(String source, Map<String, String> names) {
            for (Map.Entry<String, String> e : names.entrySet()) {
                String[] owner = owners.get(e.getKey());
                if (owner != null && !owner[0].equals(source)) {
                    throw new CollisionException(e.getKey(), owner[1], e.getValue());
                }
            }
            release(source);
            names.forEach((name, origin) -> owners.put(name, new String[]{source, origin}));
        }

        /** Frees the names of a source (it was disabled). */
        public synchronized void release(String source) {
            owners.values().removeIf(o -> o[0].equals(source));
        }

        /** Description of the tool holding {@code name}, null when free. */
        public synchronized String owner(String name) {
            String[] o = owners.get(name);
            return o == null ? null : o[1];
        }
    }

    /** Collects the names of one source, failing on the first name given twice. Not thread-safe. */
    static final class SourceNames {
        private final Map<String, String> names = new LinkedHashMap<>();

        /** @return the sanitized name of {@code raw} */
        String add(String raw, String origin) {
            String name = of(raw);
            String previous = names.putIfAbsent(name, origin);
            if (previous != null) throw new CollisionException(name, previous, origin);
            return name;
        }

        Map<String, String> names() {
            return names;
        }
    }
}
