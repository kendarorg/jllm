package org.kendar.llm.harness.context.mnemonic;

/** Persistence port of the mnemonic decisions, keyed by chat and agent. */
public interface MnemonicStore {

    /** @return the saved state, or a new empty one */
    MnemonicState load(String chatId, String agentKey);

    void save(String chatId, String agentKey, MnemonicState state);

    void delete(String chatId);
}
