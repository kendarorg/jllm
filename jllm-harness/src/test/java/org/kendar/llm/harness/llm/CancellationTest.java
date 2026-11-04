package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.agent.AgentLoop;
import org.kendar.llm.harness.agent.SubAgentTool;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.ToolKind;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CancellationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private final CountDownLatch release = new CountDownLatch(1);

    @TempDir
    Path base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            if (status == 200) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
            byte[] out = "{}".getBytes();
            try {
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
            } catch (Exception ignored) {
                // client went away
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private AnthropicModelClient anthropic(int retries, long backoff) {
        AnthropicSettings p = new AnthropicSettings("k", baseUrl(), "m", 100, retries, "5m", 0, 60, true);
        return new AnthropicModelClient(p, mapper, backoff);
    }

    private OpenAiCompatModelClient openai(int retries, long backoff) {
        return new OpenAiCompatModelClient(new LlmSettings("t", baseUrl() + "/v1", "k", "m", 100, retries, 60,
                true, true), mapper, backoff);
    }

    private ModelRequest req() {
        ArrayNode m = mapper.createArrayNode();
        m.addObject().put("role", "user").put("content", "q");
        return new ModelRequest("pin", "", m, java.util.List.of(), 0);
    }

    private void assertCancelsFast(ModelClient c, long cancelAfterMs) throws Exception {
        CancelToken token = new CancelToken();
        CompletableFuture<ModelResponse> f = CompletableFuture.supplyAsync(() -> c.complete(req(), token));
        Thread.sleep(cancelAfterMs);
        long t0 = System.nanoTime();
        token.cancel();
        try {
            f.get(5, TimeUnit.SECONDS);
            fail("expected cancellation");
        } catch (ExecutionException e) {
            assertInstanceOf(ModelCancelledException.class, e.getCause());
        } catch (TimeoutException e) {
            fail("call was not aborted");
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3000);
    }

    @Test
    void anthropicInFlightCallIsAborted() throws Exception {
        assertCancelsFast(anthropic(0, 1), 500);
        assertEquals(1, hits.get());
    }

    @Test
    void openAiInFlightCallIsAborted() throws Exception {
        assertCancelsFast(openai(0, 1), 500);
    }

    @Test
    void backoffSleepIsAborted() throws Exception {
        status = 500;
        assertCancelsFast(anthropic(5, 60_000), 700);
        assertEquals(1, hits.get());
        hits.set(0);
        assertCancelsFast(openai(5, 60_000), 700);
        assertEquals(1, hits.get());
    }

    @Test
    void alreadyCancelledTokenDoesNotSend() {
        CancelToken t = new CancelToken();
        t.cancel();
        assertThrows(ModelCancelledException.class, () -> anthropic(0, 1).complete(req(), t));
        assertEquals(0, hits.get());
    }

    @Test
    void legacyImplementationsStillWorkViaDefaultOverload() {
        ModelClient legacy = new ModelClient() {
            public ModelResponse complete(ModelRequest r) {
                return text("ok");
            }

            public String simpleComplete(String s, String u, int m) {
                return "";
            }
        };
        assertEquals("end_turn", legacy.complete(req(), new CancelToken()).stopReason());
    }

    // ---- agent loop -----------------------------------------------------------------------------------------

    private AgentLoop loopWith(Rig rig, ModelClient model) {
        return rig.loopWith(model);
    }

    @Test
    void loopCancelAbortsBlockedModelCallAndLeavesLogConsistent() throws Exception {
        Rig rig = new Rig(base);
        String chat = rig.newChat();
        CountDownLatch inCall = new CountDownLatch(1);
        ModelClient blocking = new ModelClient() {
            public ModelResponse complete(ModelRequest r) {
                return complete(r, CancelToken.NONE);
            }

            public ModelResponse complete(ModelRequest r, CancelToken c) {
                CountDownLatch never = new CountDownLatch(1);
                c.onCancel(never::countDown);
                inCall.countDown();
                try {
                    never.await();
                } catch (InterruptedException ignored) {
                }
                throw new ModelCancelledException();
            }

            public String simpleComplete(String s, String u, int m) {
                return "";
            }
        };
        AgentLoop loop = loopWith(rig, blocking);
        CancelToken token = new CancelToken();
        Events ev = new Events();
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> loop.run(chat, "hi", ev, token));
        assertTrue(inCall.await(5, TimeUnit.SECONDS));
        token.cancel();
        run.get(5, TimeUnit.SECONDS);

        assertTrue(ev.types().contains("cancelled"));
        assertFalse(ev.types().contains("error"));
        ArrayNode stored = rig.sessions.messages(rig.sessions.get(chat));
        assertEquals(1, stored.size());
    }

    @Test
    void loopCancelInterruptsRunningToolAndPairsToolResult() throws Exception {
        Rig rig = new Rig(base);
        String chat = rig.newChat();
        CountDownLatch inTool = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        rig.register(tool("slow", "Misc", ToolKind.READ_ONLY, (c, in) -> {
            inTool.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            return "late";
        }));
        rig.model.enqueue(toolUse("tu1", "slow", "{}"));
        CancelToken token = new CancelToken();
        Events ev = new Events();
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> rig.loop.run(chat, "go", ev, token));
        assertTrue(inTool.await(5, TimeUnit.SECONDS));
        token.cancel();
        run.get(5, TimeUnit.SECONDS);

        assertTrue(ev.types().contains("cancelled"));
        assertEquals(1, rig.model.requests.size(), "no further model call after cancel");
        ArrayNode stored = rig.sessions.messages(rig.sessions.get(chat));
        assertEquals(3, stored.size());
        var tr = stored.get(2).get("content").get(0);
        assertEquals("tu1", tr.get("tool_use_id").asText());
        assertTrue(tr.get("is_error").asBoolean());
        long deadline = System.currentTimeMillis() + 3000;
        while (!interrupted.get() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(interrupted.get(), "tool thread was interrupted");
    }
}
