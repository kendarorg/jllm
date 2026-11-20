package org.kendar.llm.eval.suite;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.HookResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.kendar.llm.eval.grade.Checks.*;
import static org.kendar.llm.eval.suite.Suite.*;

/** 1.12 Hooks and verify-on-stop (Java hooks; command hooks need the trust file). */
class HooksCasesTest extends SuiteBase {

    @Override
    public List<EvalCase> cases() {
        return List.of(
                smoke(HOOKS, "pre-tool-deny")
                        .harness(b -> b.hook(HookEvent.PRE_TOOL, ctx ->
                                "write_file".equals(ctx.tool()) ? HookResult.deny("writes are frozen") : HookResult.allow()))
                        .user("Create a file named a.txt containing hello")
                        .check(fileMissing("a.txt")).check(anyToolResultContains("Denied by a hook: writes are frozen"))
                        .build(),
                full(HOOKS, "pre-tool-rewrites-input")
                        .harness(b -> b.hook(HookEvent.PRE_TOOL, ctx -> {
                            if (!"write_file".equals(ctx.tool())) return HookResult.allow();
                            ObjectNode in = ctx.input().deepCopy();
                            in.put("content", "REWRITTEN-BY-HOOK");
                            return HookResult.modifyInput(in);
                        }))
                        .user("Create a file named a.txt containing exactly: original")
                        .check(fileContains("a.txt", "REWRITTEN-BY-HOOK")).build(),
                full(HOOKS, "stop-hook-veto-then-finish")
                        .harness(b -> {
                            AtomicInteger n = new AtomicInteger();
                            b.hook(HookEvent.STOP, ctx -> n.getAndIncrement() == 0
                                    ? HookResult.continueWith("Also create the file verified.txt with the text ok before finishing.")
                                    : HookResult.allow());
                        })
                        .user("Create a file named main.txt containing start, then finish.")
                        .check(fileContains("main.txt", "start")).check(fileContains("verified.txt", "ok"))
                        .check(eventPresent("verify_failed")).check(runOutcome("completed")).build(),
                full(HOOKS, "edge-throwing-hook-ignored")
                        .harness(b -> b.hook(HookEvent.PRE_TOOL, ctx -> {
                            throw new IllegalStateException("hook bug");
                        }))
                        .setup(files("a.txt", "value 64\n"))
                        .user("Read a.txt and tell me the value.")
                        .check(answerContains("64")).check(runOutcome("completed")).build(),
                full(HOOKS, "edge-user-prompt-adds-context")
                        .harness(b -> b.hook(HookEvent.USER_PROMPT, ctx -> HookResult.addContext(
                                "The theme word of this session is PINEAPPLE-22.")))
                        .user("What is the theme word of this session?")
                        .check(answerContains("PINEAPPLE-22")).build(),
                full(HOOKS, "risk-unfixable-stop-check-ends-unverified")
                        .harness(b -> b.hook(HookEvent.STOP, ctx -> HookResult.continueWith(
                                "The stop check failed: the release checklist is incomplete.")))
                        .user("Say done.")
                        .check(runOutcome("unverified")).check(eventPresent("verify_failed")).check(maxToolCalls(10))
                        .risk().build());
    }
}
