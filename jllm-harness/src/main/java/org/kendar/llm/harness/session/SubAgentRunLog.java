package org.kendar.llm.harness.session;

import java.util.List;

/** Audit-trail port of sub-agent runs. Recording must never fail the run it describes. */
public interface SubAgentRunLog {

    void record(SubAgentRunData run);

    List<SubAgentRunData> list(String chatId);

    List<SubAgentRunData> forToolUse(String chatId, String toolUseId);

    void deleteChat(String chatId);

    /** Discards everything. */
    static SubAgentRunLog noop() {
        return new SubAgentRunLog() {
            @Override public void record(SubAgentRunData run) { }
            @Override public List<SubAgentRunData> list(String chatId) { return List.of(); }
            @Override public List<SubAgentRunData> forToolUse(String chatId, String toolUseId) { return List.of(); }
            @Override public void deleteChat(String chatId) { }
        };
    }
}
