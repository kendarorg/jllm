package org.kendar.llm.cli;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs {@link Main#run} on its own thread with stdin, stdout and stderr replaced: lines are typed one at a time, the
 * output is awaited, and the end-of-process cleanup runs when the session ends. {@code jllm.home} points to
 * {@code home}, so the user's own global settings, trust answers and logins are never read nor written.
 */
final class CliSession implements AutoCloseable {

    private static final long TIMEOUT_MS = 20_000;

    private final Typed input = new Typed();
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8);
    private final List<Runnable> cleanups = new CopyOnWriteArrayList<>();
    private final InputStream savedIn = System.in;
    private final PrintStream savedOut = System.out;
    private final PrintStream savedErr = System.err;
    private final String savedHome = System.getProperty("jllm.home");
    private final Consumer<Runnable> savedAtExit = Main.atExit;
    private final java.util.function.BiFunction<List<String>, Path, Term> savedTerminal = Main.terminal;
    private Thread thread;
    private volatile Throwable failure;
    private volatile Integer exit;
    private int mark;
    private boolean restored;

    CliSession(Path home) {
        System.setProperty("jllm.home", home.toString());
    }

    /** Lines typed before {@link #start} are waiting on stdin when the CLI starts (as piped input). */
    CliSession type(String line) {
        input.add(line + "\n");
        return this;
    }

    /** Runs {@link Main#run}: what it throws is kept for {@link #failure}, the exit code for {@link #exitCode}. */
    CliSession start(String... args) {
        return launch(() -> Main.run(args, System.currentTimeMillis()));
    }

    /** Runs {@link Main#exitCode}, as {@code main} does: failures become exit codes and the result file. */
    CliSession startProcess(String... args) {
        return launch(() -> Main.exitCode(args));
    }

    private CliSession launch(java.util.concurrent.Callable<Integer> body) {
        System.setIn(input);
        System.setOut(capture);
        System.setErr(capture);
        Main.atExit = cleanups::add;
        Main.terminal = (commands, history) -> null; // plain input and output, as with a pipe
        thread = new Thread(() -> {
            try {
                exit = body.call();
            } catch (Throwable t) {
                failure = t;
            }
        }, "cli-under-test");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    /** Waits for {@code text} after what earlier calls matched; the output so far. */
    String await(String text) {
        long end = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < end) {
            String s = output();
            int i = s.indexOf(text, mark);
            if (i >= 0) {
                mark = i + text.length();
                return s;
            }
            if (!thread.isAlive() && s.indexOf(text, mark) < 0) {
                fail("the CLI ended before printing '" + text + "' (" + failure + "):\n" + s);
            }
            pause();
        }
        return fail("no '" + text + "' within " + TIMEOUT_MS + " ms:\n" + output());
    }

    String output() {
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** Ends stdin and waits for the CLI to return; rethrows what it threw. */
    String end() throws Throwable {
        input.eof();
        thread.join(TIMEOUT_MS);
        if (thread.isAlive()) fail("the CLI did not end:\n" + output());
        close();
        if (failure != null) throw failure;
        return output();
    }

    /** Waits for the CLI to return; its exit code, null when it threw. */
    Integer exitCode() throws InterruptedException {
        input.eof();
        thread.join(TIMEOUT_MS);
        if (thread.isAlive()) fail("the CLI did not end:\n" + output());
        return exit;
    }

    /** What {@link Main#run} threw; null when it returned. */
    Throwable failure() throws InterruptedException {
        thread.join(TIMEOUT_MS);
        return failure;
    }

    @Override
    public void close() {
        if (restored) return;
        restored = true;
        input.eof();
        if (thread != null) {
            try {
                thread.join(TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        cleanups.forEach(Runnable::run); // closes the harness and releases the history lock
        System.setIn(savedIn);
        System.setOut(savedOut);
        System.setErr(savedErr);
        Main.atExit = savedAtExit;
        Main.terminal = savedTerminal;
        if (savedHome == null) System.clearProperty("jllm.home");
        else System.setProperty("jllm.home", savedHome);
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A stdin whose bytes arrive when typed; {@link #available} counts what is waiting. */
    private static final class Typed extends InputStream {
        private static final int EOF = -1;
        private final LinkedBlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();

        void add(String s) {
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) bytes.add(b & 0xff);
        }

        void eof() {
            bytes.add(EOF);
        }

        @Override
        public int read() {
            try {
                int b = bytes.take();
                if (b == EOF) bytes.add(EOF); // stays at the end for every later read
                return b;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return EOF;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) return 0;
            int first = read();
            if (first == EOF) return EOF;
            b[off] = (byte) first;
            int n = 1;
            while (n < len) {
                Integer next = bytes.peek();
                if (next == null || next == EOF) break;
                b[off + n++] = (byte) (int) bytes.poll();
            }
            return n;
        }

        @Override
        public int available() {
            Integer head = bytes.peek();
            return head == null || head == EOF ? 0 : bytes.size();
        }
    }
}
