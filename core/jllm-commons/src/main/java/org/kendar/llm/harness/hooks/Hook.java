package org.kendar.llm.harness.hooks;

@FunctionalInterface
public interface Hook {

    /** A hook that throws is logged and ignored; it never breaks a run. */
    HookResult run(HookContext ctx) throws Exception;
}
