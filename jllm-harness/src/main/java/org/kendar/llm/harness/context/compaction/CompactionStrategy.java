package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.context.ContextStrategy;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.context.ContextTurn;
import org.kendar.llm.harness.context.ContextView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The tiered compaction of {@link ContextManager}: the log itself is rewritten and saved. */
public class CompactionStrategy implements ContextStrategy {

    private static final Logger log = LoggerFactory.getLogger(CompactionStrategy.class);

    private final ContextManager manager;
    private final HarnessConfig config;
    private final ObjectMapper mapper;

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
        boolean changed = t.main()
                ? manager.compactIfNeeded(t.chatId(), t.log(), t.lastUsage(), t.sink(), t.cancelled(), t.usageSink(),
                true, t.prefix())
                : manager.compactInherited(t.chatId(), t.log(), t.inherited(), t.lastUsage(), t.sink(), t.usageSink(),
                t.prefix());
        return new ContextView(t.log(), changed, changed);
    }

    @Override
    public ContextView onOverflow(ContextTurn t) {
        if (!config.compaction().auto()) return null;
        try {
            CompactResult r = manager.compactNow(t.log(), null, t.usageSink());
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
        return manager.compactNow(t.log(), focus, t.usageSink(), t.prefix());
    }

    @Override
    public long contextTokens(String chatId, String agentKey, ArrayNode log) {
        return manager.estimateTokens(log, null);
    }
}
