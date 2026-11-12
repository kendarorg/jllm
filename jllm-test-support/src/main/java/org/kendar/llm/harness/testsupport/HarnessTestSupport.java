package org.kendar.llm.harness.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/** Shared fakes for plugin tests: a scripted model, response builders, a tool builder and a test config. */
public final class HarnessTestSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private HarnessTestSupport() {
    }

    /** Parses JSON where single quotes stand for double quotes. */
    public static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static ModelResponse text(String t) {
        ArrayNode c = MAPPER.createArrayNode();
        c.addObject().put("type", "text").put("text", t);
        return new ModelResponse(c, "end_turn", new Usage(10, 5, 0, 0));
    }

    public record Recorded(String pinned, String extra, ArrayNode messages, List<String> toolNames, int maxTokens) {
    }

    /** Queue-driven (or function-driven) fake of the model client. Records deep copies of every request. */
    public static final class ScriptedModel implements ModelClient {
        public final Deque<ModelResponse> queue = new ArrayDeque<>();
        public final Deque<String> simpleReplies = new ArrayDeque<>();
        public final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
        public final List<String> simpleUserPrompts = Collections.synchronizedList(new ArrayList<>());
        public volatile Function<ModelRequest, ModelResponse> handler;
        public volatile Function<String, String> simpleHandler;

        public ScriptedModel enqueue(ModelResponse... rs) {
            synchronized (queue) {
                queue.addAll(Arrays.asList(rs));
            }
            return this;
        }

        @Override
        public ModelResponse complete(ModelRequest r) {
            requests.add(new Recorded(r.pinnedSystem(), r.extraSystem(), r.messages().deepCopy(),
                    r.tools().stream().map(HarnessTool::name).toList(), r.maxTokens()));
            if (handler != null) return handler.apply(r);
            synchronized (queue) {
                ModelResponse next = queue.poll();
                if (next == null) throw new IllegalStateException("No scripted model response left");
                return next;
            }
        }

        @Override
        public String simpleComplete(String system, String user, int maxTokens) {
            simpleUserPrompts.add(user);
            if (simpleHandler != null) return simpleHandler.apply(user);
            String s = simpleReplies.poll();
            if (s == null) throw new IllegalStateException("No scripted simple reply left");
            return s;
        }
    }

    public static HarnessConfig config(Path baseDir) {
        return HarnessConfig.defaults().withAgentId("jllm").withLocalDirSandboxAllowed(false).withMaxTokens(1024)
                .withApprovalTimeoutSeconds(5)
                .withUseContextStack(false)
                .withContextBudgetTokens(150_000).withSandboxBaseDir(baseDir.toString())
                .withSandboxTimeoutSeconds(10)
                // sanitization has its own tests; plugin tests assert on the raw tool output
                .withOutput(new HarnessConfig.Output(30_000, 2_000, "off", true, java.util.List.of()));
    }

    public static SimpleTool tool(String name, String group, ToolKind kind, SimpleTool.Body body) {
        return new SimpleTool(name, name + " tool", SimpleTool.schema("x?", "anything"), group, kind, body);
    }
}
