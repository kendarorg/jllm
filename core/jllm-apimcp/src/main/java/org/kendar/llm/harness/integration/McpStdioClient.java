package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * MCP client over stdio: starts the server as a child process and exchanges newline-delimited JSON-RPC messages on
 * its stdin and stdout. The process is started on the first {@link #initialize()} and stopped by {@link #close()}.
 * <p>
 * A server that exits after a successful start is restarted on the next call; when {@value #MAX_RESTART_TRIES}
 * restarts in a row fail, the client becomes unavailable: it reports the reason once to
 * {@link #onUnavailable(Consumer)} and every call throws {@link McpUnavailableException}.
 */
public final class McpStdioClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpStdioClient.class);
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final long TIMEOUT_SECONDS = 60;
    static final int MAX_RESTART_TRIES = 3;
    private static final long RESTART_DELAY_MS = 500;

    private final ObjectMapper mapper;
    private final String name;
    private final List<String> command;
    private final Map<String, String> env;
    private final List<String> inheritedEnv;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /** Held while starting and initializing the server, which waits for its answer: {@link #close()} never takes it. */
    private final ReentrantLock lifecycle = new ReentrantLock();
    /** Guards the process and stdin fields and the writes; only ever held briefly. */
    private final Object io = new Object();
    private final AtomicReference<String> unavailable = new AtomicReference<>();
    private volatile Consumer<String> onUnavailable = reason -> { };
    private volatile boolean closed;
    private volatile Process process;
    /** The last process whose output ended (it exited or closed stdout). */
    private volatile Process ended;
    private volatile BufferedWriter stdin;
    private volatile boolean initialized;
    /** Whether a start ever succeeded: only a server that worked is restarted. */
    private volatile boolean started;

    public McpStdioClient(ObjectMapper mapper, String name, String executable, List<String> args,
                          Map<String, String> env) {
        this(mapper, name, executable, args, env, null);
    }

    /**
     * @param inheritedEnv patterns of the variables of this process the server inherits ({@code null}: all); the
     *                     server's own {@code env} is added on top
     */
    public McpStdioClient(ObjectMapper mapper, String name, String executable, List<String> args,
                          Map<String, String> env, List<String> inheritedEnv) {
        this.inheritedEnv = org.kendar.llm.harness.util.EnvWhitelist.orAll(inheritedEnv);
        this.mapper = mapper;
        this.name = name;
        this.command = new ArrayList<>();
        this.command.add(executable);
        this.command.addAll(args);
        this.env = Map.copyOf(env);
    }

    /**
     * Called once, with the reason, when the server could not be restarted after it exited. The source of this client
     * should then be disabled. The callback runs on the calling thread, outside the locks of this client.
     */
    public McpStdioClient onUnavailable(Consumer<String> callback) {
        this.onUnavailable = callback == null ? reason -> { } : callback;
        return this;
    }

    /** Why the client gave up, null while it is usable. */
    public String unavailableReason() {
        return unavailable.get();
    }

    @Override
    public void initialize() throws IOException, InterruptedException {
        checkUsable();
        String gaveUp = null;
        IOException last = null;
        lifecycle.lockInterruptibly();
        try {
            checkUsable();
            if (initialized && alive()) return;
            if (!started) {
                startAndInitialize();
                started = true;
                return;
            }
            for (int attempt = 1; attempt <= MAX_RESTART_TRIES; attempt++) {
                log.warn("MCP server '{}' is not running; restarting it (try {} of {})", name, attempt,
                        MAX_RESTART_TRIES);
                try {
                    startAndInitialize();
                    return;
                } catch (IOException e) {
                    if (closed) throw closedException();
                    last = e;
                    log.warn("MCP server '{}' restart failed: {}", name, e.getMessage());
                    if (attempt < MAX_RESTART_TRIES) Thread.sleep(RESTART_DELAY_MS * attempt);
                }
            }
            String reason = "could not be restarted after " + MAX_RESTART_TRIES + " tries: "
                    + (last == null ? "unknown error" : last.getMessage());
            if (unavailable.compareAndSet(null, reason)) gaveUp = reason;
        } finally {
            lifecycle.unlock();
        }
        if (gaveUp != null) {
            log.warn("MCP server '{}' is unavailable: {}", name, gaveUp);
            try {
                onUnavailable.accept(gaveUp);
            } catch (RuntimeException e) {
                log.warn("MCP server '{}': unavailable callback failed: {}", name, e.toString());
            }
        }
        throw new McpUnavailableException("MCP server '" + name + "' is unavailable: " + unavailable.get(), last);
    }

    /** Starts a fresh process and runs the handshake; on failure the process is stopped. Lifecycle lock held. */
    private void startAndInitialize() throws IOException, InterruptedException {
        initialized = false;
        stopProcess();
        try {
            start();
            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.putObject("capabilities");
            params.putObject("clientInfo").put("name", "jllm-harness").put("version", "1");
            request("initialize", params);
            ObjectNode note = mapper.createObjectNode();
            note.put("jsonrpc", "2.0").put("method", "notifications/initialized");
            write(note);
            initialized = true;
        } catch (IOException | InterruptedException | RuntimeException e) {
            stopProcess();
            throw e;
        }
    }

    private void checkUsable() throws IOException {
        if (closed) throw closedException();
        String reason = unavailable.get();
        if (reason != null) throw new McpUnavailableException("MCP server '" + name + "' is unavailable: " + reason);
    }

    private IOException closedException() {
        return new IOException("MCP server '" + name + "' is closed");
    }

    private boolean alive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    @Override
    public List<JsonNode> listTools() throws IOException, InterruptedException {
        initialize();
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = request("tools/list", params);
            result.path("tools").forEach(tools::add);
            cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
        } while (cursor != null);
        return tools;
    }

    @Override
    public JsonNode callTool(String toolName, JsonNode arguments) throws IOException, InterruptedException {
        initialize();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments);
        return request("tools/call", params);
    }

    /** Never waits for a pending start or handshake: it fails them and stops the process. */
    @Override
    public void close() {
        closed = true;
        initialized = false;
        Process running = process;
        if (running != null) running.destroy(); // also unblocks a write stuck on a full pipe
        failPending("MCP server '" + name + "' was closed");
        stopProcess();
    }

    private void stopProcess() {
        Process p;
        synchronized (io) {
            p = process;
            process = null;
            stdin = null;
        }
        if (p == null) return;
        failPending("MCP server '" + name + "' was stopped");
        p.destroy();
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    private void start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        org.kendar.llm.harness.util.EnvWhitelist.apply(pb.environment(), inheritedEnv);
        pb.environment().putAll(env);
        Process p = pb.start();
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        synchronized (io) {
            if (closed) {
                p.destroyForcibly();
                throw closedException();
            }
            process = p;
            stdin = w;
        }
        startDaemon("mcp-stdout-" + name, () -> readStdout(p));
        startDaemon("mcp-stderr-" + name, () -> drainStderr(p));
    }

    private void startDaemon(String threadName, Runnable body) {
        Thread t = new Thread(body, threadName);
        t.setDaemon(true);
        t.start();
    }

    private void readStdout(Process p) {
        try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                dispatch(line);
            }
        } catch (IOException e) {
            log.debug("MCP server '{}' output failed: {}", name, e.toString());
        }
        ended = p; // every answer it sent has been dispatched
        synchronized (io) {
            if (process != p) return; // stopped or replaced: whoever did it handled the pending calls
            initialized = false;
        }
        if (!closed) log.warn("MCP server '{}' exited; it will be restarted on the next call", name);
        failPending("MCP server '" + name + "' exited");
    }

    private void dispatch(String line) {
        if (line.isBlank()) return;
        JsonNode message;
        try {
            message = mapper.readTree(line);
        } catch (IOException e) {
            log.debug("MCP server '{}' sent a non-JSON line: {}", name, line);
            return;
        }
        if (message.hasNonNull("method") || !message.hasNonNull("id")) return;
        CompletableFuture<JsonNode> waiting = pending.remove(message.get("id").asLong());
        if (waiting != null) waiting.complete(message);
    }

    private void drainStderr(Process p) {
        try (BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = err.readLine()) != null) {
                log.debug("MCP server '{}': {}", name, line);
            }
        } catch (IOException e) {
            log.debug("MCP server '{}' stderr failed: {}", name, e.toString());
        }
    }

    private void failPending(String reason) {
        for (Long id : new ArrayList<>(pending.keySet())) {
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null) f.completeExceptionally(new IOException(reason));
        }
    }

    private JsonNode request(String method, JsonNode params) throws IOException, InterruptedException {
        long id = ids.incrementAndGet();
        CompletableFuture<JsonNode> waiting = new CompletableFuture<>();
        pending.put(id, waiting);
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0").put("id", id).put("method", method);
        req.set("params", params);
        try {
            write(req);
        } catch (IOException e) {
            pending.remove(id);
            throw e;
        }
        // The output may have ended before this call was registered: nobody would answer or fail it.
        Process p = process;
        if (closed || p == null || p == ended) {
            pending.remove(id);
            waiting.completeExceptionally(new IOException("MCP server '" + name + "' "
                    + (closed ? "was closed" : "exited")));
        }
        JsonNode message;
        try {
            message = waiting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IOException("MCP server '" + name + "' did not answer " + method + " within "
                    + TIMEOUT_SECONDS + " s");
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        }
        if (message.hasNonNull("error")) {
            JsonNode err = message.get("error");
            throw new IOException("MCP error: " + err.path("message").asText(err.toString()));
        }
        return message.path("result");
    }

    private void write(JsonNode message) throws IOException {
        String line = mapper.writeValueAsString(message);
        synchronized (io) {
            BufferedWriter w = stdin;
            if (w == null) throw new IOException("MCP server '" + name + "' is not running");
            w.write(line);
            w.write('\n');
            w.flush();
        }
    }
}
