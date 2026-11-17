package org.kendar.llm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Interpretation of typed lines and of y/N answers. */
final class LineCommands {

    /** Put in the input queue when stdin ended. */
    static final String EOF = "<eof>";
    /** Put in the input queue by ctrl+c during an approval question: the question was answered "no". */
    static final String INTERRUPTED = "<interrupted>";

    private LineCommands() {
    }

    /** The text of {@code /steer <text>} or {@code /followup <text>}; null for any other line or an empty text. */
    static String idleQueuedText(String line) {
        String s = line.strip();
        for (String c : new String[]{"/steer", "/followup"}) {
            if (s.equals(c) || !s.startsWith(c + " ")) continue;
            String text = s.substring(c.length()).strip();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    static boolean isExitCommand(String line) {
        String s = line.strip();
        return s.equals("/exit") || s.equals("/quit");
    }

    /** {@code /expand} or {@code /expand N}. */
    static boolean isExpandCommand(String line) {
        String s = line.strip();
        return s.equals("/expand") || s.startsWith("/expand ");
    }

    /** The argument of an expand command, as {@link Renderer#expand} takes it (empty for the last call). */
    static String expandArg(String line) {
        return line.strip().substring("/expand".length());
    }

    /** Whether an answer starts with y (any case); an answer that is {@link #EOF} is no. */
    static boolean startsWithYes(String answer) {
        return !EOF.equals(answer) && answer.strip().toLowerCase(Locale.ROOT).startsWith("y");
    }

    /** True for y/yes, false for anything else, null when there is no answer. */
    static Boolean parseYesNo(String answer) {
        if (answer == null) return null;
        String t = answer.strip().toLowerCase(Locale.ROOT);
        return t.equals("y") || t.equals("yes");
    }

    /**
     * Reads one line byte by byte so nothing typed after it is swallowed before the chat's own stdin reader starts.
     *
     * @return the line, or null when the stream ended before any byte
     */
    static String readPlainLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        int b = in.read();
        if (b == -1) return null;
        for (; b != -1 && b != '\n'; b = in.read()) line.write(b);
        return line.toString(StandardCharsets.UTF_8);
    }

    /** Asks on a plain stream: the question, then the answer line; null when the input ended. */
    static Boolean askYesNo(String question, PrintStream out, InputStream in) {
        try {
            out.print(question + " [y/N] ");
            out.flush();
            return parseYesNo(readPlainLine(in));
        } catch (IOException e) {
            return null;
        }
    }
}
