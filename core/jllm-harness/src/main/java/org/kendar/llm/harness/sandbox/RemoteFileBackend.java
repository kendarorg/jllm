package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link FileBackend} over the exec daemon of a container. The daemon is resolved lazily, on the first call. */
public final class RemoteFileBackend implements FileBackend {

    /** Supplies the daemon client, starting the container if needed. */
    @FunctionalInterface
    public interface ClientSupplier {
        DaemonClient get() throws IOException;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final ClientSupplier client;
    private final String workRoot;
    private final String scratchBase;
    private final String agentId;

    /**
     * @param scratchBase container path of the scratchpad ({@code /workspace/.kendhar/scratchpad})
     * @param agentId     the agent whose scratch directory {@link Root#SCRATCH} means; null: the whole scratchpad
     */
    public RemoteFileBackend(ClientSupplier client, String workRoot, String scratchBase, String agentId) {
        this.client = client;
        this.workRoot = workRoot;
        this.scratchBase = scratchBase;
        this.agentId = agentId;
    }

    @Override
    public String workRoot() {
        return workRoot;
    }

    @Override
    public String scratchRoot() {
        return agentId == null ? scratchBase : scratchBase + "/" + agentId;
    }

    @Override
    public Target readTarget(String path) {
        return new Target(Root.WORKSPACE, path);
    }

    @Override
    public Target writeTarget(String path, boolean planMode) {
        if (!planMode) return new Target(Root.WORKSPACE, path);
        String scratch = scratchRoot();
        String rel;
        if (path.startsWith("/")) {
            String n = normalize(path);
            if (!n.equals(scratch) && !n.startsWith(scratch + "/")) {
                throw new IllegalArgumentException("Plan mode: the working directory is read-only; write under "
                        + scratch + " instead");
            }
            rel = n.substring(scratch.length());
        } else {
            rel = normalize("/" + path);
            if (rel.equals("/..") || rel.startsWith("/../")) {
                throw new IllegalArgumentException("Path escapes the scratch dir: " + path);
            }
        }
        String inScratch = rel.startsWith("/") ? rel.substring(1) : rel;
        String full = agentId == null ? inScratch : (inScratch.isEmpty() ? agentId : agentId + "/" + inScratch);
        return new Target(Root.SCRATCH, full);
    }

    /** POSIX normalization of an absolute path; {@code ..} above the root stays as a leading {@code ..}. */
    static String normalize(String path) {
        List<String> out = new ArrayList<>();
        for (String seg : path.replace('\\', '/').split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!out.isEmpty() && !out.get(out.size() - 1).equals("..")) out.remove(out.size() - 1);
                else out.add("..");
            } else {
                out.add(seg);
            }
        }
        return "/" + String.join("/", out);
    }

    // ── calls ─────────────────────────────────────────────────────────────────

    private static Map<String, String> q(Target t, String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("path", t.path());
        if (t.root() == Root.SCRATCH) m.put("root", "scratch");
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private DaemonClient.Response send(String method, String path, Map<String, String> query, byte[] body,
                                       String contentType) throws IOException {
        try {
            DaemonClient.Response r = client.get().call(method, path, query, body, contentType, TIMEOUT);
            if (r.status() == 200) return r;
            String msg = errorOf(r);
            switch (r.status()) {
                case 400, 409 -> throw new IllegalArgumentException(msg);
                case 404 -> throw new NoSuchFileException(msg);
                case 401 -> throw new IOException("The sandbox daemon rejected the token");
                default -> throw new IOException("Sandbox daemon error " + r.status() + ": " + msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static String errorOf(DaemonClient.Response r) {
        try {
            JsonNode n = JSON.readTree(r.body());
            if (n != null && n.hasNonNull("error")) return n.get("error").asText();
        } catch (IOException ignored) {
            // not json
        }
        return r.text();
    }

    private static JsonNode json(DaemonClient.Response r) throws IOException {
        return JSON.readTree(r.body());
    }

    @Override
    public Stat stat(Target t) throws IOException {
        JsonNode n = json(send("GET", "/stat", q(t), null, null));
        if (!n.path("exists").asBoolean(false)) return new Stat(false, null, 0, 0, null);
        return new Stat(true, n.path("type").asText(), n.path("size").asLong(), n.path("mtime").asLong(),
                n.path("mode").asText());
    }

    @Override
    public byte[] read(Target t, long offset, long limit) throws IOException {
        return send("GET", "/file", q(t, "offset", String.valueOf(offset), "limit", String.valueOf(limit)), null, null)
                .body();
    }

    @Override
    public void write(Target t, byte[] data, boolean append) throws IOException {
        send("PUT", "/file", q(t, "append", append ? "1" : "0"), data, "application/octet-stream");
    }

    @Override
    public List<String> glob(String pattern) throws IOException {
        return lines(send("GET", "/glob", Map.of("pattern", pattern), null, null));
    }

    @Override
    public List<String> grep(String pattern, String path, String glob) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pattern", pattern);
        if (path != null) body.put("path", path);
        if (glob != null) body.put("glob", glob);
        return lines(send("POST", "/grep", null, JSON.writeValueAsBytes(body), "application/json"));
    }

    private static List<String> lines(DaemonClient.Response r) {
        String s = r.text();
        return s.isEmpty() ? List.of() : List.of(s.split("\n", -1));
    }

    @Override
    public JsonNode list(Target t, boolean all) throws IOException {
        return json(send("GET", "/list", q(t, "format", "json", "all", all ? "1" : "0"), null, null));
    }

    @Override
    public String tree(Target t, int depth) throws IOException {
        return send("GET", "/tree", q(t, "depth", String.valueOf(depth)), null, null).text();
    }

    @Override
    public void mkdir(Target t) throws IOException {
        send("POST", "/mkdir", q(t), null, null);
    }

    @Override
    public void move(Root root, String from, String to) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        if (root == Root.SCRATCH) m.put("root", "scratch");
        send("POST", "/move", m, null, null);
    }

    @Override
    public void delete(Target t) throws IOException {
        send("DELETE", "/file", q(t), null, null);
    }
}
