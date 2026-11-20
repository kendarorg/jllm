package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.gate.GateResultsHandler;
import org.kendar.llm.harness.sandbox.LocalShell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * The actions and choosers that come with the plugin, configured per step through {@code with}:
 * <ul>
 *   <li>{@value #GATE} chooser: reads the value at {@code from} and maps it to an outcome with a
 *       {@link GateResultsHandler}; never reads prose.</li>
 *   <li>{@value #RUN_COMMAND} action: runs {@code command} through the platform shell and returns
 *       {@code command}, {@code exitCode}, {@code timedOut}, {@code durationMs} and {@code tail}.</li>
 * </ul>
 */
public final class BuiltinActions implements ActionProvider {

    public static final String GATE = "gate";
    public static final String RUN_COMMAND = "run-command";
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;
    public static final int DEFAULT_TAIL_LINES = 50;
    /** Longer output lines are cut, so one huge line cannot fill the memory. */
    static final int MAX_LINE = 4000;
    /** How long the output is still read after the command ended or was killed. */
    private static final long READER_GRACE_MS = 2000;

    @Override
    public void register(Registry registry) {
        registry.chooser(GATE, (Registry.ExplainingChooser) BuiltinActions::gate);
        registry.action(RUN_COMMAND, BuiltinActions::runCommand);
    }

    static Registry.Choice gate(Registry.ActionCall call) {
        JsonNode with = call.params();
        String from = with.path("from").asText("");
        GateResultsHandler.Result r = new GateResultsHandler(GateResultsHandler.Config.fromJson(with))
                .decide(call.context().resolve(from));
        ObjectNode d = Json.M.createObjectNode();
        d.put("gate", from).put("response", r.response());
        d.set("value", r.value());
        d.put("fellBack", r.fellBack()).put("reason", r.reason());
        return new Registry.Choice(r.response(), d);
    }

    static JsonNode runCommand(Registry.ActionCall call) throws IOException, InterruptedException {
        JsonNode with = call.params();
        String command = with.path("command").asText("");
        if (command.isBlank()) throw new IllegalArgumentException("run-command needs a command");
        double timeout = with.path("timeoutSeconds").asDouble(DEFAULT_TIMEOUT_SECONDS);
        if (!(timeout > 0)) throw new IllegalArgumentException("timeoutSeconds must be positive");
        int tailLines = Math.max(0, with.path("tailLines").asInt(DEFAULT_TAIL_LINES));
        Path cwd = Path.of(with.path("cwd").asText(".")).toAbsolutePath().normalize();
        if (!Files.isDirectory(cwd)) throw new IllegalArgumentException("Working directory " + cwd + " does not exist");
        LocalShell shell = LocalShell.of(with.path("shell").asText(null));

        long t0 = System.nanoTime();
        Process p = new ProcessBuilder(shell.argv(command)).directory(cwd.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        Tail tail = new Tail(tailLines, p.getInputStream());
        Thread reader = new Thread(tail, "run-command-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = p.waitFor(Math.max(1, Math.round(timeout * 1000)), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            kill(p);
            throw e;
        }
        if (!finished) kill(p);
        reader.join(READER_GRACE_MS); // a background child holding the pipe must not block the flow
        if (reader.isAlive()) p.getInputStream().close();
        ObjectNode o = Json.M.createObjectNode();
        o.put("command", command).put("exitCode", finished ? p.exitValue() : -1).put("timedOut", !finished);
        o.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        o.put("tail", tail.text());
        return o;
    }

    private static void kill(Process p) throws InterruptedException {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        p.waitFor(5, TimeUnit.SECONDS);
    }

    /** Keeps the last lines of the output. */
    private static final class Tail implements Runnable {
        private final int max;
        private final InputStream in;
        private final Deque<String> lines = new ArrayDeque<>();

        Tail(int max, InputStream in) {
            this.max = max;
            this.in = in;
        }

        @Override
        public void run() {
            Charset cs = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()),
                    Charset.defaultCharset());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, cs))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (max == 0) continue;
                    if (line.length() > MAX_LINE) line = line.substring(0, MAX_LINE) + "...";
                    synchronized (lines) {
                        lines.addLast(line);
                        if (lines.size() > max) lines.removeFirst();
                    }
                }
            } catch (IOException ignored) {
                // stream closed: keep what was read
            }
        }

        String text() {
            synchronized (lines) {
                return String.join("\n", lines);
            }
        }
    }
}
