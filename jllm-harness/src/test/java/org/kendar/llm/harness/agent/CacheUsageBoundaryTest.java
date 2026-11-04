package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.llm.AnthropicSettings;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.llm.AnthropicModelClient;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.tools.ToolKind;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.HarnessTestSupport.tool;
import org.kendar.llm.harness.HarnessTestSupport.CountingMetrics;
import static org.mockito.Mockito.when;

/**
 * Real AnthropicModelClient against a fake HTTP server returning scripted usage metadata
 * (cache creation first, cache reads later), driven through the full agent loop incl. tier-2 compaction.
 */
class CacheUsageBoundaryTest {

    @TempDir
    Path base;

    HttpServer server;
    final Deque<String> responses = new ConcurrentLinkedDeque<>();
    final List<JsonNode> sent = Collections.synchronizedList(new ArrayList<>());
    CountingMetrics metrics;
    Rig rig;
    String chatId;

    private static String reply(String contentJson, String stop, int in, int out, int read, int create) {
        return "{\"content\":" + contentJson + ",\"stop_reason\":\"" + stop + "\",\"usage\":{\"input_tokens\":" + in
                + ",\"output_tokens\":" + out + ",\"cache_read_input_tokens\":" + read
                + ",\"cache_creation_input_tokens\":" + create + "}}";
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            sent.add(MAPPER.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = responses.poll().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();

        AnthropicSettings p = new AnthropicSettings("k", "http://127.0.0.1:" + server.getAddress().getPort(),
                "claude-test", 1024, 5, "5m", 0, 10, true);
        AnthropicModelClient client = new AnthropicModelClient(p, MAPPER, 1);

        metrics = new CountingMetrics();
        rig = new Rig(base, metrics);
        rig.configure(c -> c.withContextBudgetTokens(1000));
        rig.configure(c -> c.withKeepRecentTurns(1));
        rig.register(tool("echo", "Misc", ToolKind.READ_ONLY, (c, in) -> "r"));
        rig.model.handler = client::complete;       // scripted rig, real wire format
        rig.model.simpleReplies.add("SUMMARY");     // tier-2 summary
        chatId = rig.newChat();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private double counter(String type) {
        return metrics.tokens(type);
    }

    @Test
    void usageAccumulatesAndPrefixBytesSurviveTier2Compaction() throws Exception {
        responses.add(reply("[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"echo\",\"input\":{}}]", "tool_use", 10, 5, 0, 400));
        responses.add(reply("[{\"type\":\"text\",\"text\":\"a1\"}]", "end_turn", 20, 6, 400, 30));
        responses.add(reply("[{\"type\":\"text\",\"text\":\"SUMMARY\"}]", "end_turn", 0, 0, 0, 0)); // tier 2
        responses.add(reply("[{\"type\":\"text\",\"text\":\"a2\"}]", "end_turn", 30, 7, 430, 0));

        Events e1 = new Events();
        rig.loop.run(chatId, "q1 " + "p".repeat(6000), e1, () -> false);
        Events e2 = new Events();
        rig.loop.run(chatId, "q2", e2, () -> false);

        assertEquals(2, e2.ofType("compaction").get(0).get("tier").asInt());
        assertEquals(4, sent.size());

        // (a) usage plumbing
        ChatSessionData s = rig.sessions.get(chatId);
        assertEquals(60, s.inputTokens());
        assertEquals(18, s.outputTokens());
        assertEquals(830, s.cacheReadTokens());
        assertEquals(430, s.cacheCreationTokens());
        assertEquals(830, counter("cache_read"));
        assertEquals(430, counter("cache_creation"));
        assertEquals(60, counter("input"));
        assertEquals(18, counter("output"));
        JsonNode u = e1.ofType("usage").get(1);
        assertEquals(400, u.get("cacheReadTokens").asLong());
        assertEquals(30, u.get("cacheCreationTokens").asLong());

        // (b) wire format: cache breakpoint on the last system block and last tool only, never in messages
        for (JsonNode body : sent) {
            JsonNode system = body.get("system");
            assertEquals(2, system.size());
            assertEquals(rig.sessions.pinnedPrefix(rig.sessions.get(chatId)), system.get(0).get("text").asText());
            assertTrue(system.get(0).path("cache_control").isMissingNode());
            assertEquals("ephemeral", system.get(1).at("/cache_control/type").asText());
            JsonNode tools = body.get("tools");
            assertEquals("ephemeral", tools.get(tools.size() - 1).at("/cache_control/type").asText());
            for (int i = 0; i < tools.size() - 1; i++) assertTrue(tools.get(i).path("cache_control").isMissingNode());
            assertFalse(MAPPER.writeValueAsString(body.get("messages")).contains("cache_control"));
        }

        // stable prefix: system + tools bytes identical before/after the message log was rewritten
        String system0 = MAPPER.writeValueAsString(sent.get(0).get("system"));
        String tools0 = MAPPER.writeValueAsString(sent.get(0).get("tools"));
        for (JsonNode body : sent) {
            assertEquals(system0, MAPPER.writeValueAsString(body.get("system")));
            assertEquals(tools0, MAPPER.writeValueAsString(body.get("tools")));
        }
        assertNotEquals(MAPPER.writeValueAsString(sent.get(1).get("messages").get(0)),
                MAPPER.writeValueAsString(sent.get(3).get("messages").get(0)));
        assertTrue(sent.get(3).get("messages").get(0).toString().contains("SUMMARY"));
        // the summary request is the normal request (same history) plus the instruction as the last user turn
        JsonNode summaryAsk = sent.get(2).get("messages");
        assertTrue(summaryAsk.get(summaryAsk.size() - 1).toString().contains("## Objective"));
    }
}
