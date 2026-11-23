package org.kendar.llm.harness;

import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.context.mnemonic.MnemonicStrategy;
import org.kendar.llm.harness.context.scope.ScopeStrategy;
import org.kendar.llm.harness.todo.TodoTools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptComposerTest {

    private static final String BASE = Prompts.load("system-prompt.md");

    @Test
    void plainConfigurationIsTheBasePromptAlone() {
        assertEquals(BASE, SystemPromptComposer.compose(null, false, "", ContextStrategyKind.COMPACTION, false, ""));
    }

    @Test
    void replacementDropsTheBasePromptButKeepsTheRest() {
        String p = SystemPromptComposer.compose("MY BASE", true, "extra", ContextStrategyKind.COMPACTION, false, "");
        assertTrue(p.startsWith("MY BASE"));
        assertFalse(p.contains(BASE));
        assertTrue(p.contains(Prompts.load("small-model.md")));
        assertTrue(p.endsWith("extra"));
    }

    @Test
    void partsFollowInAFixedOrder() {
        String p = SystemPromptComposer.compose(null, true, "ADDENDUM", ContextStrategyKind.MNEMONIC, true, "RULES");
        int small = p.indexOf(Prompts.load("small-model.md"));
        int add = p.indexOf("ADDENDUM");
        int mnemonic = p.indexOf(MnemonicStrategy.systemAddendum());
        int todos = p.indexOf(TodoTools.systemAddendum());
        int user = p.indexOf("## User instructions");
        assertTrue(0 < small && small < add && add < mnemonic && mnemonic < todos && todos < user, p);
        assertTrue(p.endsWith("RULES"));
    }

    @Test
    void blankAddendumAddsNothing() {
        assertEquals(BASE, SystemPromptComposer.compose(null, false, "  \n", ContextStrategyKind.COMPACTION, false, ""));
    }

    @Test
    void scopeStrategyAddsItsNoteAndMnemonicDoesNot() {
        String scope = SystemPromptComposer.compose(null, false, "", ContextStrategyKind.SCOPE, false, "");
        assertTrue(scope.endsWith(ScopeStrategy.systemAddendum()));
        assertFalse(scope.contains(MnemonicStrategy.systemAddendum()));
    }

    @Test
    void userInstructionsGetTheirOwnHeadingAndComeLast() {
        String p = SystemPromptComposer.compose(null, false, "", ContextStrategyKind.SCOPE, true, "Always answer in Latin.");
        assertTrue(p.contains("\n\n## User instructions\n\n"));
        assertTrue(p.endsWith("\n\nAlways answer in Latin."));
        assertTrue(p.indexOf(TodoTools.systemAddendum()) < p.indexOf("## User instructions"));
    }
}
