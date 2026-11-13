package org.kendar.llm.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Loads the prompt resources under {@code /agent-harness/prompts/}. A line starting with {@code "# "} (or a lone
 * {@code #}) is a comment and is dropped; {@code ##} and deeper are markdown headings and are kept.
 */
public final class Prompts {

    private static final String BASE = "/agent-harness/prompts/";
    private static final Pattern COMMENT = Pattern.compile("^#(\\s.*)?$");

    private Prompts() {
    }

    /** The prompt in {@code /agent-harness/prompts/<name>}, without comments and surrounding blank lines. */
    public static String load(String name) {
        try (var in = Prompts.class.getResourceAsStream(BASE + name)) {
            if (in == null) throw new IllegalStateException("Missing prompt resource " + BASE + name);
            return strip(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String strip(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\R", -1)) {
            if (COMMENT.matcher(line).matches()) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }
}
