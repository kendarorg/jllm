package org.kendar.llm.eval.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelClients;
import org.kendar.llm.harness.llm.OpenAiCompatModelClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Scripted OpenAI-compatible chat server: answers the n-th request with the n-th scripted reply, so a case can
 * force what a local server does (cut a response off at the output limit, stop in the middle of a tool call) without
 * depending on the model under test. After the script it answers "done" with finish reason {@code stop}.
 * Every request body is logged as an operation named {@code chat}.
 */
public final class FakeLlmServer extends FakeServer {

    /** Completion tokens reported for a cut-off reply; equal to the harness output limit of the eval runner. */
    public static final int LIMIT_TOKENS = 4096;

    private static final ObjectMapper M = new ObjectMapper();

    private record Step(String content, String toolName, String toolArguments, String finish) {
    }

    private final List<Step> script = new ArrayList<>();
    private int next;

    public FakeLlmServer() {
        super("llm");
    }

    /** A text reply ending normally. */
    public FakeLlmServer text(String content) {
        script.add(new Step(content, null, null, "stop"));
        return this;
    }

    /** A text reply cut off at the output limit ({@code finish_reason=length}). */
    public FakeLlmServer cutText(String content) {
        script.add(new Step(content, null, null, "length"));
        return this;
    }

    /** A tool call whose arguments stop in the middle, cut off at the output limit. */
    public FakeLlmServer cutToolCall(String tool, String partialArgumentsJson) {
        script.add(new Step(null, tool, partialArgumentsJson, "length"));
        return this;
    }

    /** For {@code EvalCase.model}: rewinds the script and returns a client of this server, once per trial. */
    public Supplier<ModelClient> client() {
        return () -> {
            reset();
            return new OpenAiCompatModelClient(ModelClients.ollamaSettings(url(), "fake-local"), M).streaming(false);
        };
    }

    /** Request bodies received so far, in order. */
    public List<String> requestBodies() {
        return operationCalls("chat").stream().map(ServerCall::body).toList();
    }

    @Override
    public synchronized void reset() {
        super.reset();
        next = 0;
    }

    @Override
    protected Reply handle(String method, String path, String query, String body) {
        if (!"POST".equals(method) || !path.endsWith("/chat/completions")) {
            return new Reply(404, "text/plain", "not found", Map.of(), "protocol", path);
        }
        Step s;
        int n;
        synchronized (this) {
            s = next < script.size() ? script.get(next) : new Step("done", null, null, "stop");
            n = ++next;
        }
        ObjectNode root = M.createObjectNode();
        root.put("id", "fake-" + n).put("object", "chat.completion").put("model", "fake-local");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0).put("finish_reason", s.finish());
        ObjectNode msg = choice.putObject("message");
        msg.put("role", "assistant");
        if (s.content() != null) msg.put("content", s.content());
        else msg.putNull("content");
        if (s.toolName() != null) {
            ArrayNode calls = msg.putArray("tool_calls");
            ObjectNode call = calls.addObject();
            call.put("id", "call_" + n).put("type", "function");
            call.putObject("function").put("name", s.toolName()).put("arguments", s.toolArguments());
        }
        boolean cut = "length".equals(s.finish());
        root.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", cut ? LIMIT_TOKENS : 20)
                .put("total_tokens", cut ? 100 + LIMIT_TOKENS : 120);
        return Reply.json(200, root.toString(), "call", "chat");
    }
}
