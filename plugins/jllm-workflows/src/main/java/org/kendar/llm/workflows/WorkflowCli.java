package org.kendar.llm.workflows;

import org.kendar.llm.harness.workflows.WorkflowApi.QuestionKind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Command line for flows. Usage:
 * <pre>
 * [--dir D] [--user U] [--role R]... [--ask] [--scope S] command args
 * commands: list | inputs NAME | start NAME [JSON] | resume RUN ANSWER | request RUN KIND [JSON] | history RUN | cancel RUN
 * </pre>
 * With {@code --ask} a wait point is answered on the spot; otherwise the run id is printed and the run stays paused.
 */
public final class WorkflowCli {
    private final Registry registry;

    public WorkflowCli(Registry registry) {
        this.registry = registry;
    }

    public static void main(String[] args) {
        System.exit(new WorkflowCli(ActionProvider.load(WorkflowCli.class.getClassLoader())).run(args, System.in, System.out));
    }

    /** @return the exit code */
    public int run(String[] args, InputStream in, PrintStream out) {
        String dir = System.getProperty("user.home") + "/.jllm-workflows";
        String user = System.getProperty("user.name");
        String scope = null;
        boolean ask = false;
        Set<String> roles = new HashSet<>();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> dir = args[++i];
                case "--user" -> user = args[++i];
                case "--role" -> roles.add(args[++i]);
                case "--scope" -> scope = args[++i];
                case "--ask" -> ask = true;
                default -> rest.add(args[i]);
            }
        }
        if (rest.isEmpty()) {
            out.println("commands: list | inputs NAME | start NAME [JSON] | resume RUN ANSWER | request RUN KIND [JSON] | history RUN | cancel RUN");
            return 2;
        }
        Path base = Path.of(dir);
        Workflows w = new Workflows(registry, new RunStore.Files(base.resolve("runs")),
                new FlowStore.Files(base.resolve("flows")), u -> roles, "admin", null);
        BufferedReader rd = new BufferedReader(new InputStreamReader(in));
        try {
            switch (rest.get(0)) {
                case "list" -> w.list(user).forEach(e -> out.println(e.scope() + "\t" + e.name()));
                case "inputs" -> Workflows.inputs(w.resolve(user, rest.get(1), scope)).forEach(out::println);
                case "start" -> show(w, user, w.start(user, rest.get(1), scope,
                        Json.parse(rest.size() > 2 ? rest.get(2) : "{}")), ask, rd, out);
                case "resume" -> {
                    JsonNode pending = w.run(user, rest.get(1)).pending;
                    if (pending == null) throw new FlowException(FlowException.Kind.INVALID_FLOW, "Run is not waiting");
                    show(w, user, w.answer(user, rest.get(1), parseAnswer(Question.fromJson(pending), rest.get(2))),
                            ask, rd, out);
                }
                case "request" -> {
                    var o = w.request(user, rest.get(1), rest.get(2), Json.parse(rest.size() > 3 ? rest.get(3) : "null"));
                    out.println("handled by " + o.handledBy() + ": " + o.action());
                    if (o.answer() != null) out.println(o.answer());
                }
                case "history" -> {
                    for (JsonNode e : w.history(user, rest.get(1))) {
                        out.println(e.path("at").asText() + "\t" + e.path("step").asText() + "\t"
                                + e.path("kind").asText() + "\t" + e.path("detail").asText());
                    }
                }
                case "cancel" -> {
                    w.cancel(user, rest.get(1));
                    out.println("cancelled " + rest.get(1));
                }
                default -> {
                    out.println("unknown command " + rest.get(0));
                    return 2;
                }
            }
            return 0;
        } catch (FlowException | IOException e) {
            out.println("error: " + e.getMessage());
            return 1;
        }
    }

    /** Text stays text; single is the option value; multi is a JSON array or a comma list. */
    static JsonNode parseAnswer(Question q, String raw) {
        if (q.kind() == QuestionKind.MULTI) {
            if (raw.trim().startsWith("[")) return Json.parse(raw);
            ArrayNode a = Json.M.createArrayNode();
            for (String s : raw.split(",")) if (!s.isBlank()) a.add(s.trim());
            return a;
        }
        return Json.M.getNodeFactory().textNode(raw);
    }

    private void show(Workflows w, String user, FlowEngine.RunResult r, boolean ask, BufferedReader rd, PrintStream out)
            throws IOException {
        while (true) {
            for (FlowEngine.Event e : r.history()) out.println("  " + e.step() + " " + e.kind() + " " + e.detail());
            switch (r.status()) {
                case COMPLETED -> {
                    out.println("completed " + r.runId() + " " + r.result());
                    return;
                }
                case FAILED -> {
                    out.println("failed " + r.runId() + " " + r.error());
                    return;
                }
                case CANCELLED -> {
                    out.println("cancelled " + r.runId());
                    return;
                }
                default -> {
                }
            }
            Question q = r.pending();
            if (!ask) {
                out.println("paused " + r.runId() + " waiting for: " + q.text());
                return;
            }
            out.println(q.text());
            if (q.kind() != QuestionKind.TEXT) {
                for (int i = 0; i < q.options().size(); i++) {
                    out.println("  " + (i + 1) + ") " + q.options().get(i).label());
                }
                out.println(q.kind() == QuestionKind.MULTI ? "numbers, comma separated:" : "number:");
            }
            String line = rd.readLine();
            if (line == null) {
                out.println("paused " + r.runId());
                return;
            }
            try {
                r = w.answer(user, r.runId(), toAnswer(q, line));
            } catch (FlowException e) {
                out.println("refused: " + e.getMessage());
            }
        }
    }

    /** Numbered lists map to option values. */
    static JsonNode toAnswer(Question q, String line) {
        if (q.kind() == QuestionKind.TEXT) return Json.M.getNodeFactory().textNode(line);
        ArrayNode a = Json.M.createArrayNode();
        for (String s : line.split(",")) {
            if (s.isBlank()) continue;
            int i;
            try {
                i = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new FlowException(FlowException.Kind.SHAPE, "'" + s.trim() + "' is not a number");
            }
            if (i < 1 || i > q.options().size()) throw new FlowException(FlowException.Kind.SHAPE, "no option " + i);
            a.add(q.options().get(i - 1).value());
        }
        if (q.kind() == QuestionKind.SINGLE) {
            if (a.size() != 1) throw new FlowException(FlowException.Kind.SHAPE, "pick exactly one");
            return a.get(0);
        }
        return a;
    }
}
