package org.kendar.llm.harness.agent;

import org.kendar.llm.harness.permission.PermissionMode;

/**
 * One task for a sub-agent.
 *
 * @param toolUseId  id of the parent "task" tool call, for correlation; may be null
 * @param agentType  tool subset and extra instructions of the agent; null = the default agent
 * @param parentMode mode of the caller when it made the call: the sub-agent never runs looser than that. A
 *                   read-only type runs as PLAN with the read-only tools only, whatever the mode
 * @param run        stream and cancel flag of the caller: approvals are forwarded to it; null = none
 * @param runId      id the run is recorded under in the sub-agent run log
 */
public record SubAgentRequest(String chatId, String toolUseId, String prompt, AgentDefinition agentType,
                              PermissionMode parentMode, AgentRun run, String runId) {
}
