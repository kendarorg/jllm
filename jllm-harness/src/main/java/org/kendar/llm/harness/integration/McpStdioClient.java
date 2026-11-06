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

/**
 * MCP client over stdio: starts the server as a child process and exchanges newline-delimited JSON-RPC messages on
 * its stdin and stdout. The process is started on the first {@link #initialize()} and stopped by {@link #close()}.
 */
public final class McpStdioClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpStdioClient.class);
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final long TIMEOUT_SECONDS = 60;

    private final ObjectMapper mapper;
    private final String name;
    private final List<String> command;
    private final Map<String, String> env;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private Process process;
    private BufferedWriter stdin;
    private boolean initialized;

    public McpStdioClient(ObjectMapper mapper, String name, String executable, List<String> args,
                          Map<String, String> env) {
        this.mapper = mapper;
        this.name = name;
        this.command = new ArrayList<>();
        this.command.add(executable);
        this.command.addAll(args);
        this.env = Map.copyOf(env);
    }

    @Override
    public synchronized void initialize() throws IOException, InterruptedException {
        if (initialized) return;
        if (closed) throw new IOException("MCP server '" + name + "' is closed");
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

    @Override
    public synchronized void close() {
        closed = true;
        initialized = false;
        failPending("MCP server '" + name + "' was closed");
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }

    private void start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startDaemon("mcp-stdout-" + name, () -> readStdout(process));
        startDaemon("mcp-stderr-" + name, () -> drainStderr(process));
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
        closed = true;
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
        pending.values().forEach(f -> f.completeExceptionally(new IOException(reason)));
        pending.clear();
    }

    private JsonNode request(String method, JsonNode params) throws IOException, InterruptedException {
        long id = ids.incrementAndGet();
        CompletableFuture<JsonNode> waiting = new CompletableFuture<>();
        pending.put(id, waiting);
        if (closed) failPending("MCP server '" + name + "' exited");
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0").put("id", id).put("method", method);
        req.set("params", params);
        try {
            write(req);
        } catch (IOException e) {
            pending.remove(id);
            throw e;
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

    private synchronized void write(JsonNode message) throws IOException {
        if (stdin == null) throw new IOException("MCP server '" + name + "' is not running");
        stdin.write(mapper.writeValueAsString(message));
        stdin.write('\n');
        stdin.flush();
    }
}
