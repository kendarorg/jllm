package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.HarnessConfig;

/** Same names and meaning as {@link HarnessConfig.Mnemonic}; unset values keep the defaults. */
public record MnemonicSettings(Integer maskTrigger, Integer maskTarget, Integer minBatchTokens,
                               Integer minMaskTokens, Integer keepRecentTokens, Integer minAgeTurns,
                               Integer tailMaxTokens, Integer notesMaxTokens, Integer indexMaxTokens,
                               Integer recallMaxChars, Boolean dropOldThinking) {

    HarnessConfig.Mnemonic over(HarnessConfig.Mnemonic d) {
        return new HarnessConfig.Mnemonic(maskTrigger != null ? maskTrigger : d.maskTrigger(),
                maskTarget != null ? maskTarget : d.maskTarget(),
                minBatchTokens != null ? minBatchTokens : d.minBatchTokens(),
                minMaskTokens != null ? minMaskTokens : d.minMaskTokens(),
                keepRecentTokens != null ? keepRecentTokens : d.keepRecentTokens(),
                minAgeTurns != null ? minAgeTurns : d.minAgeTurns(),
                tailMaxTokens != null ? tailMaxTokens : d.tailMaxTokens(),
                notesMaxTokens != null ? notesMaxTokens : d.notesMaxTokens(),
                indexMaxTokens != null ? indexMaxTokens : d.indexMaxTokens(),
                recallMaxChars != null ? recallMaxChars : d.recallMaxChars(),
                dropOldThinking != null ? dropOldThinking : d.dropOldThinking());
    }
}
