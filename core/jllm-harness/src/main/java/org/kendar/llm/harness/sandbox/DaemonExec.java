package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Runs one command through the exec daemon: start, poll until it ends, release. */
final class DaemonExec {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofSeconds(20);

    private DaemonExec() {
    }

    /** @throws IOException the daemon cannot be reached (the caller forgets the container and reports it) */
    static ExecutionResult run(DaemonClient c, String command, String cwd, String logDir, Duration timeout,
                               long logLimit) throws IOException {
        String id = null;
        try {
            Map<String, Object> body = Map.of("command", command, "cwd", cwd, "logDir", logDir,
                    "timeoutSeconds", Math.max(1, timeout.toSeconds()), "logMaxBytes", Math.max(0, logLimit));
            DaemonClient.Response r = c.call("POST", "/exec", null, JSON.writeValueAsBytes(body),
                    "application/json", CONTROL_TIMEOUT);
            if (r.status() != 200) return new ExecutionResult("", "Sandbox error " + r.status() + ": " + r.text(), -1, false);
            id = JSON.readTree(r.body()).path("id").asText();
            long deadline = System.nanoTime() + timeout.plusSeconds(60).toNanos();
            while (true) {
                DaemonClient.Response p = c.call("GET", "/exec/" + id, Map.of("wait", String.valueOf(POLL.toSeconds())),
                        null, null, POLL.plusSeconds(20));
                if (p.status() != 200) return new ExecutionResult("", "Sandbox error " + p.status() + ": " + p.text(), -1, false);
                JsonNode n = JSON.readTree(p.body());
                if (!n.path("running").asBoolean(false)) {
                    String logPath = n.path("logPath").isNull() ? null : n.path("logPath").asText(null);
                    return new ExecutionResult(n.path("stdout").asText(""), "", n.path("exitCode").asInt(-1),
                            n.path("timedOut").asBoolean(false), n.path("omittedLines").asLong(0),
                            logPath == null || logPath.isEmpty() ? null : Path.of(logPath));
                }
                if (System.nanoTime() > deadline) return new ExecutionResult("", "Sandbox command did not finish", -1, true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecutionResult("", "Interrupted", -1, false);
        } finally {
            if (id != null) release(c, id);
        }
    }

    /** Kills (if still running) and forgets a command; the container stays up. */
    private static void release(DaemonClient c, String id) {
        boolean interrupted = Thread.interrupted();
        try {
            c.call("DELETE", "/exec/" + id, null, null, null, CONTROL_TIMEOUT);
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (IOException ignored) {
            // daemon gone: nothing to release
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
