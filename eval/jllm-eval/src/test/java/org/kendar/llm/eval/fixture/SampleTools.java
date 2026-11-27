package org.kendar.llm.eval.fixture;

import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom tools whose kind is honest: a pure lookup is READ_ONLY, a file write is FILE_EDIT, a side effect on the
 * outside world is OTHER. Every execution is counted, so a check can tell whether a tool ran.
 */
public final class SampleTools {

    public static final String GROUP = "Sample";

    /** An email "sent" by {@code send_email}; nothing leaves the process. */
    public record Email(String to, String subject, String body) {
    }

    private final Map<String, Integer> counts = new HashMap<>();
    private final List<Email> outbox = new ArrayList<>();
    private final int flakyFailures;
    private int flakyAttempts;

    /** @param flakyFailures how many first calls of {@code flaky_lookup} fail before it recovers */
    public SampleTools(int flakyFailures) {
        this.flakyFailures = flakyFailures;
    }

    public SampleTools() {
        this(1);
    }

    /** time_offset (READ_ONLY), flaky_lookup (READ_ONLY), write_note (FILE_EDIT), send_email (OTHER). */
    public List<HarnessTool> all() {
        return List.of(timeOffset(), flakyLookup(), writeNote(), sendEmail());
    }

    private HarnessTool timeOffset() {
        return new SimpleTool("time_offset", "Returns the UTC offset of a city.",
                SimpleTool.schema("city", "City name, e.g. Tokyo"), GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            count("time_offset");
            return switch (in.path("city").asText().toLowerCase()) {
                case "tokyo" -> "UTC+9";
                case "lisbon" -> "UTC+0";
                case "new york" -> "UTC-5";
                default -> "unknown city";
            };
        });
    }

    private HarnessTool flakyLookup() {
        return new SimpleTool("flaky_lookup", "Looks up a key; the service can fail transiently, retrying helps.",
                SimpleTool.schema("key", "Key to look up"), GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            count("flaky_lookup");
            if (nextFlakyAttempt() <= flakyFailures) throw new IllegalStateException("temporarily unavailable, try again");
            return "value-of-" + in.path("key").asText();
        });
    }

    private HarnessTool writeNote() {
        return new SimpleTool("write_note", "Writes a note to a file in the workspace.",
                SimpleTool.schema("file", "Relative file name", "text", "Text of the note"), GROUP,
                ToolKind.FILE_EDIT, (ctx, in) -> {
            count("write_note");
            Path target = ctx.workDir().resolve(in.path("file").asText()).normalize();
            if (!target.startsWith(ctx.workDir().normalize())) throw new IllegalArgumentException("outside the workspace");
            Files.writeString(target, in.path("text").asText());
            return "saved " + in.path("file").asText();
        });
    }

    private HarnessTool sendEmail() {
        return new SimpleTool("send_email", "Sends an email.",
                SimpleTool.schema("to", "Recipient", "subject", "Subject", "body", "Body"), GROUP,
                ToolKind.OTHER, (ctx, in) -> {
            count("send_email");
            synchronized (outbox) {
                outbox.add(new Email(in.path("to").asText(), in.path("subject").asText(), in.path("body").asText()));
            }
            return "sent to " + in.path("to").asText();
        });
    }

    private synchronized void count(String tool) {
        counts.merge(tool, 1, Integer::sum);
    }

    private synchronized int nextFlakyAttempt() {
        return ++flakyAttempts;
    }

    /** How many times the tool body ran. */
    public synchronized int executions(String tool) {
        return counts.getOrDefault(tool, 0);
    }

    public List<Email> outbox() {
        synchronized (outbox) {
            return List.copyOf(outbox);
        }
    }

    /** Clears counters, the outbox and the flaky state. */
    public synchronized void reset() {
        counts.clear();
        flakyAttempts = 0;
        synchronized (outbox) {
            outbox.clear();
        }
    }
}
