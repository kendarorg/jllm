package org.kendar.llm.harness.permission;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalSerializationTest {

    private static void waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(10);
        assertTrue(c.getAsBoolean());
    }

    @Test
    void oneDialogAtATimePerChatAndTheSecondShowsAfterTheFirstIsAnswered() throws Exception {
        ApprovalService svc = new ApprovalService(10);
        List<String> shown = new CopyOnWriteArrayList<>();
        CompletableFuture<ApprovalService.ApprovalDecision> a = CompletableFuture.supplyAsync(
                () -> svc.await("c", "tool", "A", "", null, p -> shown.add("A"), () -> false));
        waitFor(() -> shown.contains("A"));
        CompletableFuture<ApprovalService.ApprovalDecision> b = CompletableFuture.supplyAsync(
                () -> svc.await("c", "tool", "B", "", null, p -> shown.add("B"), () -> false));
        Thread.sleep(700);
        assertEquals(List.of("A"), shown, "B waits behind the open dialog");
        assertEquals(1, svc.pending("c").size());

        svc.resolve("c", svc.pending("c").get(0).id(), true, null);
        waitFor(() -> shown.contains("B"));
        svc.resolve("c", svc.pending("c").get(0).id(), false, null);
        assertTrue(a.get().approved());
        assertFalse(b.get().approved());
    }

    @Test
    void cancelFreesAWaitingCaller() throws Exception {
        ApprovalService svc = new ApprovalService(10);
        AtomicBoolean shown = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture.runAsync(() -> svc.await("c", "tool", "A", "", null, p -> shown.set(true), () -> false));
        waitFor(shown::get);
        CompletableFuture<ApprovalService.ApprovalDecision> b = CompletableFuture.supplyAsync(
                () -> svc.await("c", "tool", "B", "", null, p -> fail("must not be shown"), cancelled::get));
        Thread.sleep(300);
        cancelled.set(true);
        assertFalse(b.get().approved());
        svc.cancelAll("c");
    }

    @Test
    void grantsAreIsolatedPerChat() {
        ApprovalService svc = new ApprovalService(1);
        svc.grant("a", "bash");
        assertTrue(svc.granted("a", "bash"));
        assertFalse(svc.granted("b", "bash"));
        svc.forget("a");
        assertFalse(svc.granted("a", "bash"));
    }
}
