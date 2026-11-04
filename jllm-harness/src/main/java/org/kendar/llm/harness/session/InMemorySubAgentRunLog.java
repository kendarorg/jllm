package org.kendar.llm.harness.session;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemorySubAgentRunLog implements SubAgentRunLog {

    private final List<SubAgentRunData> runs = new CopyOnWriteArrayList<>();

    @Override
    public void record(SubAgentRunData run) {
        runs.add(run);
    }

    @Override
    public List<SubAgentRunData> list(String chatId) {
        return runs.stream().filter(r -> r.chatId().equals(chatId)).toList();
    }

    @Override
    public List<SubAgentRunData> forToolUse(String chatId, String toolUseId) {
        return list(chatId).stream().filter(r -> toolUseId.equals(r.toolUseId())).toList();
    }

    @Override
    public void deleteChat(String chatId) {
        runs.removeIf(r -> r.chatId().equals(chatId));
    }
}
