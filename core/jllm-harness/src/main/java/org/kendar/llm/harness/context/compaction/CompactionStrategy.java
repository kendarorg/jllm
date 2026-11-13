package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The tiered compaction of {@link ContextManager}: the log itself is rewritten and saved. */
public class CompactionStrategy implements ContextStrategy {

    private static final Logger log = LoggerFactory.getLogger(CompactionStrategy.class);

    private final ContextManager manager;
    private final HarnessConfig config;
    private final ObjectMapper mapper;
    private final TokenEstimate tokens = new TokenEstimate();

    public CompactionStrategy(ContextManager manager, HarnessConfig config, ObjectMapper mapper) {
        this.manager = manager;
        this.config = config;
        this.mapper = mapper;
    }

    public ContextManager manager() {
        return manager;
    }

    @Override
    public ContextStrategyKind kind() {
        return ContextStrategyKind.COMPACTION;
    }

    @Override
    public ContextView prepare(ContextTurn t) {
        if (t.lastUsage() != null) tokens.measured(t.chatId(), t.agentKey(), t.lastUsage().contextTokens());
        double ratio = tokens.ratio(t.chatId(), t.agentKey());
        boolean changed = t.main()
                ? manager.compactIfNeeded(t.chatId(), t.log(), t.lastUsage(), t.sink(), t.cancelled(), t.usageSink(),
                true, t.prefix(), ratio)
                : manager.compactInherited(t.chatId(), t.log(), t.inherited(), t.lastUsage(), t.sink(), t.usageSink(),
                t.prefix(), false, ceiling(), ratio, null);
        tokens.sent(t.chatId(), t.agentKey(), t.log().toString().length() + ContextManager.fixedChars(t.prefix()));
        return new ContextView(t.log(), changed, changed);
    }

    @Override
    public ContextView onOverflow(ContextTurn t) {
        if (!config.compaction().auto()) return null;
        // the estimate was too low: trust it less from now on
        tokens.overflowed(t.chatId(), t.agentKey());
        double ratio = tokens.ratio(t.chatId(), t.agentKey());
        try {
            CompactResult r;
            if (inheritsCopy(t)) {
                r = compactInherited(t, ratio);
            } else {
                long room = (long) (ceiling() / ratio) - ContextManager.fixedTokens(t.prefix());
                r = manager.compactOverflow(t.log(), room, t.usageSink());
            }
            if (!r.changed()) return null;
            t.sink().emit("compaction", mapper.createObjectNode().put("tier", 2)
                    .put("estimatedTokens", r.tokensAfter()).put("reason", "overflow"));
            return new ContextView(t.log(), true, true);
        } catch (RuntimeException e) {
            log.warn("Overflow compaction failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public CompactResult compactNow(ContextTurn t, String focus) {
        if (inheritsCopy(t)) return compactInherited(t, tokens.ratio(t.chatId(), t.agentKey()));
        return manager.compactNow(t.log(), focus, t.usageSink(), t.prefix());
    }

    /** A sub-agent that started from a copy of its parent's context: compaction must keep the copy boundary. */
    private static boolean inheritsCopy(ContextTurn t) {
        return !t.main() && t.inherited()[0] > 0;
    }

    /** Forced compaction of a sub-agent with an inherited copy; updates {@code t.inherited()[0]}. */
    private CompactResult compactInherited(ContextTurn t, double ratio) {
        long before = manager.estimateTokens(t.log(), null);
        boolean changed = manager.compactInherited(t.chatId(), t.log(), t.inherited(), null, t.sink(), t.usageSink(),
                t.prefix(), true, ceiling(), ratio, null);
        return new CompactResult(changed, before, manager.estimateTokens(t.log(), null), 0);
    }

    private long ceiling() {
        return config.compaction().ceiling(config.contextBudgetTokens());
    }

    @Override
    public long contextTokens(String chatId, String agentKey, ArrayNode log) {
        return Math.round(manager.estimateTokens(log, null) * tokens.ratio(chatId, agentKey));
    }

    @Override
    public void release(String chatId, String agentKey) {
        tokens.release(chatId, agentKey);
    }

    @Override
    public void forget(String chatId) {
        tokens.forget(chatId);
    }
}
