package org.kendar.llm.harness;

import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.context.mnemonic.MnemonicStrategy;
import org.kendar.llm.harness.context.scope.ScopeStrategy;
import org.kendar.llm.harness.todo.TodoTools;

/** Composes the system prompt of the main agent from the base prompt and the parts the configuration switches on. */
public final class SystemPromptComposer {

    private SystemPromptComposer() {
    }

    /**
     * @param replacement  replaces the built-in base prompt when not null
     * @param smallModel   adds the stricter rules of {@code small-model.md}
     * @param addendum     text appended after the base prompt; blank adds nothing
     * @param kind         the context strategy: mnemonic and scope add their own notes
     * @param todos        the todo tools are offered: adds their note
     * @param instructions rendered instruction files, put last under their own heading; blank adds nothing
     */
    public static String compose(String replacement, boolean smallModel, String addendum, ContextStrategyKind kind,
                                 boolean todos, String instructions) {
        return (replacement != null ? replacement : Prompts.load("system-prompt.md"))
                + (smallModel ? "\n\n" + Prompts.load("small-model.md") : "")
                + (addendum.isBlank() ? "" : "\n\n" + addendum)
                + (kind == ContextStrategyKind.MNEMONIC ? "\n\n" + MnemonicStrategy.systemAddendum() : "")
                + (kind == ContextStrategyKind.SCOPE ? "\n\n" + ScopeStrategy.systemAddendum() : "")
                + (todos ? "\n\n" + TodoTools.systemAddendum() : "")
                // last, under their own heading: small models skip a rule buried among the defaults
                + (instructions.isBlank() ? "" : "\n\n## User instructions\n\nThe user's standing instructions. "
                + "Follow them in every answer, also when the request does not mention them; they override the "
                + "defaults above.\n\n" + instructions);
    }
}
