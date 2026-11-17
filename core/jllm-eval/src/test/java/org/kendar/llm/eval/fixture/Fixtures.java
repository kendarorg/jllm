package org.kendar.llm.eval.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.workflows.WorkflowsPlugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Wires fixtures into an eval case:
 *
 * <pre>{@code
 * Fixtures fx = Fixtures.create().mcp(FakeMcpServer.sample("docs", "..."), true).pdf("report.pdf");
 * EvalCase.named("x").harness(fx.harness()).setup(fx.setup()) ...
 * }</pre>
 *
 * {@link #harness()} runs before the harness is built: it resets every fixture, then registers the servers, tools
 * and plugin. {@link #setup()} runs after: it writes the workspace files and puts the live fixtures into the extras
 * under the keys below, where state checks read them. Sequential use only.
 *
 * <ul>
 *   <li>{@link #serverKey(String)}: the {@link FakeServer} (live call log via {@link FakeServer#calls()})</li>
 *   <li>{@link #TOOLS}: the {@link SampleTools} (executions and outbox)</li>
 *   <li>{@link #CANARIES}: the {@code List<String>} of canaries</li>
 *   <li>{@link #FACTS}: every {@link Fact} of generated documents and of the large output</li>
 *   <li>{@link #factsKey(String)}: the facts of one generated file or of the large output</li>
 * </ul>
 */
public final class Fixtures implements AutoCloseable {

    public static final String TOOLS = "fixture.tools";
    public static final String CANARIES = "fixture.canaries";
    public static final String FACTS = "fixture.facts";
    public static final String LARGE_OUTPUT = "fixture.largeOutput";
    private static final ObjectMapper M = new ObjectMapper();
    private static final Set<Path> TEMP_DIRS = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> TEMP_DIRS.forEach(Fixtures::deleteQuietly)));
    }

    private record Source(FakeServer server, boolean readOnly, boolean downAtStart) {
    }

    private final List<Source> mcp = new ArrayList<>();
    private final List<Source> rest = new ArrayList<>();
    private final Map<String, String> files = new LinkedHashMap<>();
    private final List<String> docNames = new ArrayList<>();
    private final Canaries canaries = new Canaries();
    private SampleTools tools;
    private LargeOutput largeOutput;
    private boolean workflows;
    private Path workflowDir;

    private Fixtures() {
    }

    public static Fixtures create() {
        return new Fixtures();
    }

    public static String serverKey(String serverName) {
        return "fixture.server." + serverName;
    }

    public static String factsKey(String file) {
        return "fixture.facts." + file;
    }

    /** Adds a fake MCP server as a harness MCP source. */
    public Fixtures mcp(FakeMcpServer server, boolean readOnly) {
        mcp.add(new Source(server, readOnly, false));
        return this;
    }

    /** As {@link #mcp(FakeMcpServer, boolean)}, but the server is down when the harness starts. */
    public Fixtures mcpDownAtStart(FakeMcpServer server, boolean readOnly) {
        mcp.add(new Source(server, readOnly, true));
        return this;
    }

    /** Adds a fake REST server as a harness REST API (discovered through its own spec). */
    public Fixtures rest(FakeRestServer server) {
        rest.add(new Source(server, false, false));
        return this;
    }

    /** As {@link #rest(FakeRestServer)}, but the server is down when the harness starts. */
    public Fixtures restDownAtStart(FakeRestServer server) {
        rest.add(new Source(server, false, true));
        return this;
    }

    /** Adds the {@link SampleTools} to the harness. */
    public Fixtures sampleTools(SampleTools t) {
        this.tools = t;
        return this;
    }

    /** Adds the {@code big_report} tool; its facts go to the extras. */
    public Fixtures largeOutput(LargeOutput o) {
        this.largeOutput = o;
        return this;
    }

    /** Enables the workflows plugin with the sample flows installed in a fresh folder for every trial. */
    public Fixtures workflows() {
        this.workflows = true;
        return this;
    }

    /** A PDF with known facts is written to the workspace as {@code name}. */
    public Fixtures pdf(String name) {
        docNames.add(name);
        return this;
    }

    /** A docx with known facts is written to the workspace as {@code name}. */
    public Fixtures docx(String name) {
        docNames.add(name);
        return this;
    }

    /** Writes a text file into the workspace. */
    public Fixtures file(String name, String content) {
        files.put(name, content);
        return this;
    }

    /** Creates a canary and plants it, inside {@code template} at {@code %s}, in the workspace file. */
    public String plantCanary(String label, String file, String template) {
        String c = canaries.create(label);
        files.put(file, template.formatted(c));
        return c;
    }

    /** Creates a canary that is not planted anywhere yet (e.g. for tool output); it is listed in the extras. */
    public String canary(String label) {
        return canaries.create(label);
    }

    public Canaries canaries() {
        return canaries;
    }

    /** The server added under {@code name}. */
    public FakeServer server(String name) {
        for (Source s : concat()) if (s.server.name().equals(name)) return s.server;
        throw new IllegalArgumentException("no server " + name);
    }

    private List<Source> concat() {
        List<Source> all = new ArrayList<>(mcp);
        all.addAll(rest);
        return all;
    }

    /** For {@code EvalCase.harness}: resets the fixtures and registers servers, tools and plugin. */
    public Consumer<AgentHarness.Builder> harness() {
        return b -> {
            reset();
            ObjectNode root = M.createObjectNode();
            ArrayNode servers = root.putArray("mcpServers");
            for (Source s : mcp) {
                servers.addObject().put("name", s.server.name()).put("url", s.server.url())
                        .put("readOnly", s.readOnly);
            }
            ArrayNode apis = root.putArray("restApis");
            for (Source s : rest) {
                apis.addObject().put("name", s.server.name()).put("openApiUrl", s.server.url() + "/openapi.json");
            }
            if (workflows) {
                workflowDir = tempDir();
                SampleFlows.install(workflowDir);
                root.putObject("plugins").putObject(WorkflowsPlugin.ID).put("enabled", true).putObject("settings")
                        .put("dir", workflowDir.toString());
                b.plugin(new WorkflowsPlugin());
            }
            for (Source s : concat()) if (s.downAtStart) s.server.setDown(true);
            b.settings(settings(root));
            List<org.kendar.llm.harness.tools.HarnessTool> extra = new ArrayList<>();
            if (tools != null) extra.addAll(tools.all());
            if (largeOutput != null) extra.add(largeOutput.tool());
            if (!extra.isEmpty()) b.tools(extra);
        };
    }

    /** For {@code EvalCase.setup}: writes the workspace files and puts the live fixtures into the extras. */
    public BiConsumer<Path, Map<String, Object>> setup() {
        return (workspace, extras) -> {
            List<Fact> facts = new ArrayList<>();
            for (String name : docNames) {
                Documents.Doc d = name.endsWith(".pdf") ? Documents.pdf(workspace, name) : Documents.docx(workspace, name);
                extras.put(factsKey(name), d.facts());
                facts.addAll(d.facts());
            }
            for (var e : files.entrySet()) Canaries.plant(workspace, e.getKey(), e.getValue());
            if (largeOutput != null) {
                extras.put(factsKey("big_report"), largeOutput.facts());
                extras.put(LARGE_OUTPUT, largeOutput);
                facts.addAll(largeOutput.facts());
            }
            for (Source s : concat()) extras.put(serverKey(s.server.name()), s.server);
            if (tools != null) extras.put(TOOLS, tools);
            extras.put(CANARIES, canaries.all());
            extras.put(FACTS, List.copyOf(facts));
        };
    }

    /** Clears every call log and counter and brings the servers up. */
    public void reset() {
        for (Source s : concat()) s.server.reset();
        if (tools != null) tools.reset();
        SampleFlowActions.reset();
        if (workflowDir != null) {
            TEMP_DIRS.remove(workflowDir);
            deleteQuietly(workflowDir);
            workflowDir = null;
        }
    }

    /** Stops the servers and removes the temporary folder. */
    @Override
    public void close() {
        reset();
        for (Source s : concat()) s.server.close();
    }

    /** The live server in the extras. */
    public static FakeServer server(Map<String, Object> extras, String name) {
        return (FakeServer) extras.get(serverKey(name));
    }

    /** The canaries in the extras. */
    @SuppressWarnings("unchecked")
    public static List<String> canaries(Map<String, Object> extras) {
        return (List<String>) extras.getOrDefault(CANARIES, List.of());
    }

    /** The facts in the extras, under {@link #FACTS} or a {@link #factsKey}. */
    @SuppressWarnings("unchecked")
    public static List<Fact> facts(Map<String, Object> extras, String key) {
        return (List<Fact>) extras.getOrDefault(key, List.of());
    }

    private static HarnessSettings settings(ObjectNode root) {
        try {
            Path f = Files.createTempFile("jllm-eval-settings", ".json");
            try {
                Files.writeString(f, M.writeValueAsString(root));
                return HarnessSettings.load(f);
            } finally {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path tempDir() {
        try {
            Path p = Files.createTempDirectory("jllm-eval-flows-");
            TEMP_DIRS.add(p);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temporary folder; the OS cleans up
        }
    }
}
