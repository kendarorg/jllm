package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps the decisions for the life of the process (as JSON, so a loaded state is never shared). */
public class InMemoryMnemonicStore implements MnemonicStore {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> states = new ConcurrentHashMap<>();

    @Override
    public MnemonicState load(String chatId, String agentKey) {
        String json = states.get(key(chatId, agentKey));
        if (json == null) return new MnemonicState();
        try {
            return mapper.readValue(json, MnemonicState.class);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt mnemonic state of chat " + chatId, e);
        }
    }

    @Override
    public void save(String chatId, String agentKey, MnemonicState state) {
        try {
            states.put(key(chatId, agentKey), mapper.writeValueAsString(state));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize mnemonic state", e);
        }
    }

    @Override
    public void delete(String chatId) {
        states.keySet().removeIf(k -> k.startsWith(chatId + "\n"));
    }

    private static String key(String chatId, String agentKey) {
        return chatId + "\n" + agentKey;
    }
}
