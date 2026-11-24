package org.kendar.llm.harness.agent;

import org.kendar.llm.harness.tools.HarnessTool;

import java.util.function.Predicate;

/** Request prefix of a sub-agent that continues its parent's (context stack): fixed system text, tool filter. */
record StackFrame(String turnExtra, Predicate<HarnessTool> allowed) {
}
