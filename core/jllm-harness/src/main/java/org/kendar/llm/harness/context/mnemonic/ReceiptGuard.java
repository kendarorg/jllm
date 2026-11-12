package org.kendar.llm.harness.context.mnemonic;

import org.kendar.llm.harness.hooks.Hook;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.HookResult;

import java.util.Set;

/**
 * Blocks writes and shell commands whose input carries a receipt: the model copying a masked placeholder into a real
 * file instead of recalling the original first.
 */
final class ReceiptGuard implements Hook {

    static final Set<String> GUARDED = Set.of("write_file", "edit_file", "bash");

    @Override
    public HookResult run(HookContext ctx) {
        if (ctx.tool() == null || !GUARDED.contains(ctx.tool()) || ctx.input() == null) return HookResult.allow();
        String in = ctx.input().toString();
        if (in.contains("[masked") && in.contains(MaskPlanner.MARKER)) {
            return HookResult.deny("the input contains a masked-content receipt ('[masked … recall id=…]'). Call "
                    + HistoryRecallTool.NAME + " with that id to get the original text, then retry with the real "
                    + "content");
        }
        return HookResult.allow();
    }
}
