package org.kendar.llm.harness.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files loaded at startup (settings, skills, instruction files, agent prompts) in which the {@link ContentScanner}
 * found something. Each file is logged once, with a warning, and kept for the front ends, which show the list to the
 * user: a message, never stored with a chat nor sent to the model. The content itself is loaded unchanged.
 * <p>
 * One instance per harness, created by whoever loads the settings and handed to the builder, so a file is reported
 * once per process.
 */
public final class ContentWarnings {

    private static final Logger log = LoggerFactory.getLogger(ContentWarnings.class);

    /** Name of the event that tells a front end about a file loaded during a chat (a scoped instruction file). */
    public static final String EVENT = "content_warning";

    /** One file and what was found in it. */
    public record Warning(String file, List<ContentScanner.Finding> findings) {

        public Warning {
            findings = List.copyOf(findings);
        }

        /** {@code {file, findings: [{line, kind, excerpt}]}}: the data of a {@link #EVENT} event. */
        public ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode().put("file", file);
            ArrayNode a = n.putArray("findings");
            for (ContentScanner.Finding f : findings) {
                a.addObject().put("line", f.line()).put("kind", f.kind().name()).put("excerpt", f.excerpt());
            }
            return n;
        }
    }

    private final Map<String, Warning> warnings = new LinkedHashMap<>();

    /** Scans {@code text} and {@link #report reports} what it finds. */
    public synchronized List<ContentScanner.Finding> check(String file, String text) {
        return report(file, ContentScanner.scan(file, text));
    }

    /**
     * Keeps the findings of {@code file} and logs them, the first time the file has some; a file already reported is
     * not reported again.
     *
     * @return the findings when they were reported now; empty otherwise
     */
    public synchronized List<ContentScanner.Finding> report(String file, List<ContentScanner.Finding> findings) {
        if (findings.isEmpty() || warnings.containsKey(file)) return List.of();
        warnings.put(file, new Warning(file, findings));
        logWarning(file, findings);
        return findings;
    }

    /** The files reported so far, in order. */
    public synchronized List<Warning> all() {
        return List.copyOf(warnings.values());
    }

    /** One warning line for the file: where and what, with the excerpts (already redacted). */
    public static void logWarning(String file, List<ContentScanner.Finding> findings) {
        List<String> parts = new ArrayList<>();
        for (ContentScanner.Finding f : findings) parts.add("line " + f.line() + " " + f.kind() + ": " + f.excerpt());
        log.warn("Suspicious content in {} (loaded anyway, check it): {}", file, String.join("; ", parts));
    }
}
