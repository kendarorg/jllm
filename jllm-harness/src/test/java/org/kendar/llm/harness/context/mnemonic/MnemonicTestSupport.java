package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.agent.AgentRun;
import org.kendar.llm.harness.agent.EventSink;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.context.ContextTurn;
import org.kendar.llm.harness.context.RequestPrefix;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.ToolContext;

import java.nio.file.Path;
import java.util.List;

import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;

/** Log builders and a small-window config for the mnemonic tests. */
final class MnemonicTestSupport {

    static final String CHAT = "c1";
    static final String AGENT = "agent";

    private MnemonicTestSupport() {
    }

    /**
     * Window 20k: ceiling 17k, trigger 10.2k, target 5.1k, min batch 3.4k, tail max 5k; 1k tokens protected,
     * 100-token minimum mask, minimum age 1 assistant turn.
     */
    static HarnessConfig config() {
        return HarnessConfig.defaults().withMaxTokens(500).withContextBudgetTokens(20_000)
                .withContextStrategy(ContextStrategyKind.MNEMONIC)
                .withMnemonic(new HarnessConfig.Mnemonic(null, null, null, 100, 1000, 1, null, 300, 300, 12_000, true));
    }

    static ArrayNode log() {
        return MAPPER.createArrayNode();
    }

    static void user(ArrayNode log, String text) {
        log.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", text);
    }

    static void assistant(ArrayNode log, String text) {
        log.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text").put("text", text);
    }

    /** An assistant read_file call of {@code src/F<i>.java} and its ~1000-token result. */
    static void readPair(ArrayNode log, int i) {
        ObjectNode a = log.addObject().put("role", "assistant");
        ObjectNode use = a.putArray("content").addObject().put("type", "tool_use").put("id", "t" + i)
                .put("name", "read_file");
        use.putObject("input").put("path", "src/F" + i + ".java");
        log.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                .put("tool_use_id", "t" + i).put("content", fileText(i));
    }

    static String fileText(int i) {
        StringBuilder sb = new StringBuilder("// src/F" + i + ".java\nclass F" + i + " { int marker" + i + "Value; }\n");
        while (sb.length() < 3500) sb.append("lorem ipsum dolor sit amet ").append(i).append('\n');
        return sb.toString();
    }

    static ContextTurn turn(ArrayNode log, RequestPrefix prefix) {
        return new ContextTurn(CHAT, AGENT, log, null, null, prefix, EventSink.NOOP, () -> false, u -> { }, true);
    }

    static RequestPrefix prefix(ModelClient client) {
        return new RequestPrefix("PIN", "EXTRA", List.of(), client, false);
    }

    static ToolContext toolContext() {
        return new ToolContext(CHAT, Path.of("."), Path.of("."), false, null, PermissionMode.DEFAULT,
                new AgentRun(EventSink.NOOP, () -> false, AGENT));
    }

    static List<String> receipts(ArrayNode view) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode m : view) {
            for (JsonNode b : m.path("content")) {
                String c = b.path("content").asText("");
                if ("tool_result".equals(b.path("type").asText()) && c.startsWith("[masked:")) out.add(c);
            }
        }
        return out;
    }
}
