package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.llm.PartialToolCall;
import org.kendar.llm.harness.tools.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Keeps the part of a cut-off {@code write_file} that arrived, so the model can finish it instead of rewriting it. */
final class PartialWrite {

    static final String TOOL = "write_file";
    private static final int TAIL_LINES = 3;
    private static final int TAIL_CHARS = 400;

    private PartialWrite() {
    }

    /**
     * Saves the complete lines of the cut-off call's content under {@code <scratch>/partial/} and returns the note for
     * the model, or null when there is nothing worth keeping.
     */
    static String save(JsonNode cut, ToolContext ctx) {
        if (!TOOL.equals(cut.path("name").asText()) || ctx.scratchDir() == null) return null;
        JsonNode in = cut.path("input");
        String path = PartialToolCall.field(in, "path");
        String content = PartialToolCall.field(in, "content");
        if (path == null || path.isBlank() || content == null) return null;
        int nl = content.lastIndexOf('\n');
        if (nl < 0) return null;
        String kept = content.substring(0, nl + 1);
        Path saved;
        if (ctx.files() != null) return saveRemote(cut, ctx, path, kept);
        try {
            String id = cut.path("id").asText().replaceAll("[^A-Za-z0-9_-]", "");
            Path name = Path.of(path).getFileName();
            if (name == null) return null;
            Path dir = ctx.scratchDir().resolve("partial");
            Files.createDirectories(dir);
            saved = dir.resolve(id + "-" + name).toAbsolutePath().normalize();
            Files.writeString(saved, kept, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return message(path, kept, saved.toString());
    }

    /** Container variant: the part goes to the agent scratch directory through the file backend. */
    private static String saveRemote(JsonNode cut, ToolContext ctx, String path, String kept) {
        String id = cut.path("id").asText().replaceAll("[^A-Za-z0-9_-]", "");
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isEmpty()) return null;
        String rel = "partial/" + id + "-" + name;
        try {
            ctx.files().write(ctx.files().writeTarget(rel, true), kept.getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return message(path, kept, ctx.files().scratchRoot() + "/" + rel);
    }

    private static String message(String path, String kept, String saved) {
        long lines = kept.chars().filter(c -> c == '\n').count();
        return " Nothing was written to " + path + ", but the first " + lines + " complete line(s) of its content "
                + "are saved at " + saved + ". They end with:\n" + tail(kept) + "\nTo finish without rewriting them: "
                + "add the rest to that saved file with write_file append: true (in parts if large), then move it "
                + "to " + path + ". Or write " + path + " again in smaller parts.";
    }

    private static String tail(String kept) {
        String[] lines = kept.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - TAIL_LINES); i < lines.length; i++) sb.append(lines[i]).append('\n');
        String t = sb.toString();
        return t.length() > TAIL_CHARS ? "…" + t.substring(t.length() - TAIL_CHARS) : t;
    }
}
