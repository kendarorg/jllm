package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Types: run_started, text, tool_call, tool_result, approval_request, usage, compaction,
 * warning, error, cancelled, run_finished.
 */
public record AgentEvent(long seq, String type, JsonNode data) {
}
