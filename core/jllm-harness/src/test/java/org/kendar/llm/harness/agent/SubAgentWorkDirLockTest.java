package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.HarnessTestSupport.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Writer sub-agents in EDIT/AUTO mode run one at a time per working directory, and waiting can be cancelled. */
class SubAgentWorkDirLockTest {

    @TempDir
    Path base;

    private static ToolContext ctx(String chat, Path workDir, AgentRun run) {
        return new ToolContext(chat, workDir, workDir, false, "t-" + chat, PermissionMode.EDIT, run);
    }

    /** A loop whose sub-agents block until released, counting how many run at once. */
    private static final class Blocking {
        final AgentLoop loop = mock(AgentLoop.class);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final CountDownLatch entered;
        final CountDownLatch release = new CountDownLatch(1);

        Blocking(int expected) {
            entered = new CountDownLatch(expected);
            when(loop.runSubAgent(any(), any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } finally {
                    running.decrementAndGet();
                }
                return "done";
            });
        }
    }

    @Test
    void differentWorkDirsDoNotWaitForEachOther() throws Exception {
        Blocking b = new Blocking(2);
        SubAgentTool tool = new SubAgentTool(() -> b.loop, MAPPER);
        Path a = Files.createDirectories(base.resolve("a"));
        Path c = Files.createDirectories(base.resolve("c"));
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<String> f1 = ex.submit(() -> tool.execute(ctx("c1", a, null), json("{'prompts':['x']}")));
            Future<String> f2 = ex.submit(() -> tool.execute(ctx("c2", c, null), json("{'prompts':['y']}")));
            assertTrue(b.entered.await(5, TimeUnit.SECONDS), "both chats run their writer at once");
            b.release.countDown();
            assertEquals("ok", MAPPER.readTree(f1.get(5, TimeUnit.SECONDS)).get(0).get("status").asText());
            assertEquals("ok", MAPPER.readTree(f2.get(5, TimeUnit.SECONDS)).get(0).get("status").asText());
            assertEquals(2, b.maxRunning.get());
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void sameWorkDirRunsOneAtATime() throws Exception {
        Blocking b = new Blocking(1);
        SubAgentTool tool = new SubAgentTool(() -> b.loop, MAPPER);
        Path a = Files.createDirectories(base.resolve("same"));
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<String> f1 = ex.submit(() -> tool.execute(ctx("c1", a, null), json("{'prompts':['x']}")));
            assertTrue(b.entered.await(5, TimeUnit.SECONDS));
            Future<String> f2 = ex.submit(() -> tool.execute(ctx("c2", a.resolve("."), null), json("{'prompts':['y']}")));
            Thread.sleep(300);
            assertEquals(1, b.running.get(), "the second writer waits for the first");
            b.release.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
            assertEquals(1, b.maxRunning.get());
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void waitingForTheLockStopsOnCancel() throws Exception {
        Blocking b = new Blocking(1);
        SubAgentTool tool = new SubAgentTool(() -> b.loop, MAPPER);
        Path a = Files.createDirectories(base.resolve("cancel"));
        AtomicBoolean cancelled = new AtomicBoolean();
        AgentRun run = new AgentRun(EventSink.NOOP, cancelled::get);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<String> f1 = ex.submit(() -> tool.execute(ctx("c1", a, null), json("{'prompts':['x']}")));
            assertTrue(b.entered.await(5, TimeUnit.SECONDS));
            Future<String> f2 = ex.submit(() -> tool.execute(ctx("c2", a, run), json("{'prompts':['y']}")));
            Thread.sleep(300);
            cancelled.set(true);
            JsonNode r = MAPPER.readTree(f2.get(5, TimeUnit.SECONDS)).get(0);
            assertEquals("cancelled", r.get("status").asText(), "gave up waiting while the first still runs");
            assertEquals(1, b.running.get());
            b.release.countDown();
            f1.get(5, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
    }
}
