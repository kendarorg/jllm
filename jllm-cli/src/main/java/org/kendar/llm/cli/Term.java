package org.kendar.llm.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * The terminal: a JLine line editor whose prompt stays at the bottom while output scrolls above it, plus a pinned status
 * bar. When stdin/stdout is not a terminal (pipes, IDE consoles) {@link #open} returns null and the CLI stays plain.
 */
final class Term implements AutoCloseable {

    private final Terminal terminal;
    private final LineReader reader;
    private final Status status;

    private Term(Terminal terminal, LineReader reader) {
        this.terminal = terminal;
        this.reader = reader;
        this.status = Status.getStatus(terminal, false);
    }

    /** A JLine terminal, or null when not attached to a real terminal. */
    static Term open(List<String> commands, java.nio.file.Path historyFile) {
        try {
            Terminal t = TerminalBuilder.builder().system(true).dumb(true).build();
            if (Terminal.TYPE_DUMB.equals(t.getType()) || Terminal.TYPE_DUMB_COLOR.equals(t.getType())) {
                System.err.println("note: no terminal detected (type " + t.getType() + "); using plain input");
                t.close();
                return null;
            }
            LineReader r = LineReaderBuilder.builder().terminal(t).history(new DefaultHistory())
                    .completer(new StringsCompleter(commands))
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .variable(LineReader.SECONDARY_PROMPT_PATTERN, "  ")
                    .variable(LineReader.HISTORY_FILE, historyFile)
                    .variable(LineReader.HISTORY_SIZE, 1000)
                    .build();
            // up/down browse the history: oldest..newest, then back to the empty line being typed
            var main = r.getKeyMaps().get(LineReader.MAIN);
            for (String k : new String[]{"[A", "OA"}) main.bind(new Reference(LineReader.UP_HISTORY), k);
            for (String k : new String[]{"[B", "OB"}) main.bind(new Reference(LineReader.DOWN_HISTORY), k);
            return new Term(t, r);
        } catch (IOException | RuntimeException e) {
            System.err.println("warning: terminal line editing unavailable (" + e + "); using plain input");
            return null;
        }
    }

    /** Prints above the prompt without corrupting what is being typed. */
    void println(String s) {
        reader.printAbove(s);
    }

    /** Reads one line, or null at end of input. Ctrl+C is reported through {@code onInterrupt}; the line is dropped. */
    String readLine(String prompt, Runnable onInterrupt) {
        while (true) {
            try {
                return reader.readLine(prompt);
            } catch (UserInterruptException e) {
                onInterrupt.run();
            } catch (EndOfFileException e) {
                return null;
            }
        }
    }

    /** Binds a key sequence (JLine syntax, e.g. {@code "\e[Z"}) to an action. */
    void bind(String keys, Runnable action) {
        reader.getWidgets().put("jllm-" + keys, () -> {
            action.run();
            return true;
        });
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("jllm-" + keys), keys);
    }

    /** Replaces the pinned bottom line. */
    void status(String line) {
        if (status != null) status.update(List.of(AttributedString.fromAnsi(line)));
    }

    int width() {
        return Math.max(40, terminal.getWidth());
    }

    @Override
    public void close() {
        if (status != null) status.close();
        try {
            terminal.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * A PrintStream for code written against {@code print}: complete lines go above the prompt, the unfinished tail is
     * handed to {@code takePrompt} so the next {@link #readLine} can show it as its prompt.
     */
    static final class Tail extends PrintStream implements Supplier<String> {
        private final Term term;
        private final ByteArrayOutputStream buf;

        Tail(Term term) {
            this(term, new ByteArrayOutputStream());
        }

        private Tail(Term term, ByteArrayOutputStream buf) {
            super(buf, true, StandardCharsets.UTF_8);
            this.term = term;
            this.buf = buf;
        }

        private synchronized void drain() {
            String s = buf.toString(StandardCharsets.UTF_8);
            int nl = s.lastIndexOf('\n');
            if (nl < 0) return;
            term.println(s.substring(0, nl));
            buf.reset();
            buf.writeBytes(s.substring(nl + 1).getBytes(StandardCharsets.UTF_8));
        }

        /** The text printed since the last newline; cleared. */
        @Override
        public synchronized String get() {
            flush();
            drain();
            String tail = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            return tail;
        }

        @Override
        public synchronized void flush() {
            super.flush();
            drain();
        }
    }
}
