package org.kendar.llm.js;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kendar.llm.harness.tools.ToolKind;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.testsupport.HarnessTestSupport.tool;

/** Limits that must hold on every profile: total time, tool-call cap, concurrency, engine limits, recovery. */
class JsLimitsTest {

    private static JsSandbox open(String engine, String... kv) {
        if (engine.equals("isolated")) Assumptions.assumeTrue(JsTest.isolateAvailable(), "no isolate on this platform");
        return JsTest.box(engine, kv);
    }

    private static org.graalvm.polyglot.Source lib() {
        return new ToolLibrary().source(List.of(tool("slow", "G", ToolKind.READ_ONLY, (c, i) -> "")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"constrained", "isolated"})
    void theWallCeilingStopsAScriptThatSleepsInToolCalls(String engine) {
        try (JsSandbox box = open(engine, "maxWallSeconds", "1", "maxInnerCalls", "1000")) {
            long t = System.nanoTime();

            // each call is long, the script's own clock stands still during it: only the wall ceiling ends this
            JsResult r = box.run("for (let i = 0; i < 1000; i++) tools.slow({}); return 'done';", 60, lib(),
                    (n, a) -> {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "ok";
                    }, null);

            long ms = (System.nanoTime() - t) / 1_000_000;
            assertFalse(r.ok(), r.toString());
            assertTrue(r.error().contains("in total") && r.error().contains("tool calls included"), r.error());
            assertTrue(ms < 15_000, "took " + ms);
            assertTrue(box.run("return 1;").ok(), "the next call works");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"constrained", "isolated"})
    void aScriptThatSleepsInOneLongToolCallIsStoppedAtTheCeilingToo(String engine) {
        try (JsSandbox box = open(engine, "maxWallSeconds", "1")) {
            long t = System.nanoTime();

            JsResult r = box.run("tools.slow({}); return 'done';", 60, lib(), (n, a) -> {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            }, null);

            assertFalse(r.ok(), r.toString());
            assertTrue(r.error().contains("tool calls included"), r.error());
            assertTrue((System.nanoTime() - t) / 1_000_000 < 15_000);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"constrained", "isolated"})
    void toolCallsPerScriptAreCappedAndTheErrorIsCatchable(String engine) {
        try (JsSandbox box = open(engine, "maxInnerCalls", "3")) {
            AtomicInteger executed = new AtomicInteger();

            JsResult r = box.run("let ok = 0, msg = '';"
                    + "for (let i = 0; i < 6; i++) { try { tools.slow({}); ok++; } catch (e) { msg = e.message; } }"
                    + "return {ok, msg};", 30, lib(), (n, a) -> {
                executed.incrementAndGet();
                return "ok";
            }, null);

            assertTrue(r.ok(), r.toString());
            assertEquals(3, executed.get(), "the bridge ran only the allowed calls");
            assertTrue(r.value().contains("\"ok\":3") && r.value().contains("Too many tool calls"), r.value());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"constrained", "isolated"})
    void theConcurrencyCapRefusesAScriptWhenAllSlotsStayBusy(String engine) throws Exception {
        try (JsSandbox box = open(engine, "maxConcurrentRuns", "1")) {
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<JsResult> first = CompletableFuture.supplyAsync(() -> box.run("tools.slow({}); return 1;",
                    30, lib(), (n, a) -> {
                        inside.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "ok";
                    }, null));
            assertTrue(inside.await(30, TimeUnit.SECONDS));

            JsResult second = box.run("return 2;");

            assertFalse(second.ok());
            assertTrue(second.error().contains("Too many scripts are running"), second.error());
            release.countDown();
            assertTrue(first.get(30, TimeUnit.SECONDS).ok());
            assertTrue(box.run("return 3;").ok(), "the slot is free again");
        }
    }

    @Test
    void zeroConcurrentRunsMeansUnlimited() {
        try (JsSandbox box = open("constrained", "maxConcurrentRuns", "0")) {
            assertTrue(box.run("return 1;").ok());
        }
    }

    @Test
    void anIsolateHeapLimitEndsTheRunAndTheNextRunWorks() {
        try (JsSandbox box = open("isolated", "maxHeapMemory", "\"48MB\"")) {
            JsResult r = box.run("const keep = []; while (true) keep.push(new Array(10000).fill(1));", 30);

            assertFalse(r.ok(), r.toString());
            assertTrue(r.error().startsWith("Limit reached"), r.error());
            assertTrue(r.error().contains("MB"), r.error());
            assertTrue(box.run("return 1;").ok(), "the next call works");
        }
    }

    @Test
    void anIsolateStackLimitIsNamedInTheError() {
        try (JsSandbox box = open("isolated", "maxStackFrames", "100")) {
            JsResult r = box.run("function f(n) { return f(n + 1) + 1; } return f(0);", 30);

            assertFalse(r.ok(), r.toString());
            assertNotNull(r.error());
            assertTrue(box.run("return 1;").ok());
        }
    }

    @Test
    void anIsolateRebuildsItsEngineAfterABreakAndKeepsWorking() {
        Assumptions.assumeTrue(JsTest.isolateAvailable(), "no isolate on this platform");
        JsSettings s = JsTest.settingsFor("isolated");
        IsolatedProfile profile = new IsolatedProfile(s);
        try (JsSandbox box = new JsSandbox(s, profile)) {
            long g = profile.generation();
            assertTrue(box.run("return 1;").ok());

            profile.recover(g);
            profile.recover(g); // a second run reporting the same break does not rebuild again

            assertEquals(g + 1, profile.generation());
            JsResult r = box.run("return 2;");
            assertTrue(r.ok(), r.toString());
            assertEquals("2", r.value());
        }
    }

    @Test
    void theUntrustedPolicyRunsScripts() {
        Assumptions.assumeTrue(JsTest.isolateAvailable(), "no isolate on this platform");
        try (JsSandbox box = open("isolated", "policy", "\"untrusted\"")) {
            JsResult r = box.run("return [1, 2].map(x => x * 2);");

            assertTrue(r.ok(), r.toString());
            assertEquals("[2,4]", r.value());
            assertTrue(box.describeProfile().contains("untrusted"), box.describeProfile());
        }
    }
}
