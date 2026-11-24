package org.kendar.llm.js;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Limits that only the constrained profile enforces. */
class ConstrainedLimitsTest {

    @Test
    void statementLimitEndsTheRunWithAMessageNamingTheLimit() {
        try (JsSandbox box = JsTest.box("constrained", "statementLimit", "100000")) {
            JsResult r = box.run("while (true) {}");

            assertFalse(r.ok());
            assertTrue(r.error().contains("more than 100000 statements"), r.error());
        }
    }

    @Test
    void theStatementLimitDefaultIsOneMillion() {
        assertEquals(1_000_000L, JsSettings.defaults().statementLimit());
    }

    @Test
    void theAllocationBudgetStopsARunThatAllocatesTooMuch() {
        Assumptions.assumeTrue(MemoryWatchdog.supported());
        // the budget is read by the watchdog, which only guards a profile without engine heap limits
        Assumptions.assumeFalse(GraalProbe.probe().sandboxLimits(), "this runtime counts heap in the engine");
        try (JsSandbox box = JsTest.box("constrained", "maxRunAllocatedBytes", "\"32MB\"", "statementLimit",
                "9000000000000")) {
            long t = System.nanoTime();

            JsResult r = box.run("const keep = []; while (true) keep.push(new Array(100000).fill(1));", 60);

            assertFalse(r.ok());
            assertTrue(r.error().startsWith("Memory limit"), r.error());
            assertTrue((System.nanoTime() - t) / 1_000_000 < 30_000, "stopped promptly");
            assertTrue(box.run("return 1;").ok(), "the next call works");
        }
    }

    @Test
    void theProfileSaysWhichMemoryGuardItUses() {
        try (var p = new ConstrainedProfile(JsSettings.defaults(), false)) {
            assertTrue(p.usesWatchdog());
            assertTrue(p.describe().contains("watchdog"), p.describe());
        }
        try (var p = new ConstrainedProfile(JsSettings.defaults(), true)) {
            assertFalse(p.usesWatchdog());
        }
    }
}
