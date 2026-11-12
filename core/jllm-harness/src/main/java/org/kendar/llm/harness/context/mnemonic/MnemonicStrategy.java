package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.Prompts;
import org.kendar.llm.harness.context.*;
import org.kendar.llm.harness.context.mnemonic.MnemonicState.Mask;
import org.kendar.llm.harness.context.mnemonic.MnemonicState.Segment;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.tools.HarnessTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mnemonic context strategy (after mnemonic-proxy). The log is never rewritten: each request is rendered from
 * the log and sticky, hash-keyed decisions, so the same log always gives the same bytes and the prompt cache stays
 * valid up to the first newly masked message.
 * <ol>
 *   <li>level 1, over {@code maskTrigger}: a batch replaces the oldest tool outputs, large tool inputs and old
 *   reasoning with one-line receipts until the prompt is down to {@code maskTarget}; committed only if it frees
 *   {@code minBatchTokens}; never before the previous batch's first mask (the frontier only moves forward)</li>
 *   <li>level 2, when the response would no longer fit under the ceiling: a segment switch keeps the first user
 *   request, handoff notes written by the model, a recall index and a verbatim tail</li>
 *   <li>emergency: the newest tool results alone leave no room, they are masked too</li>
 * </ol>
 * Everything masked or moved out stays retrievable word for word through {@code history_recall}.
 */
public class MnemonicStrategy implements ContextStrategy {

    private static final Logger log = LoggerFactory.getLogger(MnemonicStrategy.class);
    static final String NOT_USED = "Not used with the mnemonic context strategy";

    /** Live state of one agent: decisions plus runtime-only data (log reference, calibration, index). */
    static final class Agent {
        final MnemonicState state;
        final boolean persistent;
        final RecallIndex index = new RecallIndex();
        volatile ArrayNode log;
        /** Characters of the last request rendered for this agent (messages + prefix). */
        long lastChars;
        /** Measured tokens / estimated tokens of the last request; 1 until a comparable measure arrives. */
        double ratio = 1.0;

        Agent(MnemonicState state, boolean persistent) {
            this.state = state;
            this.persistent = persistent;
        }
    }

    private final HarnessConfig config;
    private final ObjectMapper mapper;
    private final HarnessMetrics metrics;
    private final MnemonicStore store;
    private final Renderer renderer;
    private final MaskPlanner planner;
    private final SegmentSwitcher switcher;
    private final HistoryRecallTool recall;
    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    public MnemonicStrategy(HarnessConfig config, ObjectMapper mapper, HarnessMetrics metrics, MnemonicStore store,
                            SubAgentRunLog subAgentRuns) {
        this.config = config;
        this.mapper = mapper;
        this.metrics = metrics;
        this.store = store == null ? new InMemoryMnemonicStore() : store;
        this.renderer = new Renderer(mapper);
        HarnessConfig.Mnemonic m = config.mnemonic();
        this.planner = new MaskPlanner(new MaskPlanner.Limits(m.minMaskTokens(), m.keepRecent(config.compaction()),
                m.minAgeTurns(), m.dropOldThinking()));
        this.switcher = new SegmentSwitcher(mapper);
        this.recall = new HistoryRecallTool(this, subAgentRuns == null ? SubAgentRunLog.noop() : subAgentRuns,
                m.recallMaxChars(), mapper);
    }

    @Override
    public ContextStrategyKind kind() {
        return ContextStrategyKind.MNEMONIC;
    }

    @Override
    public List<HarnessTool> tools() {
        return List.of(recall);
    }

    @Override
    public void install(Hooks hooks) {
        hooks.add(HookEvent.PRE_TOOL, new ReceiptGuard());
    }

    /** Addendum to the system prompt explaining receipts and recall. */
    public static String systemAddendum() {
        return Prompts.load("mnemonic-suffix.md");
    }

    @Override
    public ContextView prepare(ContextTurn t) {
        Agent a = agent(t.chatId(), t.agentKey(), t.main());
        synchronized (a) {
            a.log = t.log();
            boolean changed = validate(a.state, t.log());
            calibrate(a, t.lastUsage() == null ? -1 : t.lastUsage().contextTokens());
            long window = config.contextBudgetTokens();
            long ceiling = config.compaction().ceiling(window);
            HarnessConfig.Mnemonic m = config.mnemonic();
            int from = Math.max(t.inherited()[0], 0);
            ArrayNode view = renderer.render(t.log(), a.state);
            long est = estimate(a, view, t.prefix());

            if (est > m.trigger(ceiling)) {
                List<Mask> batch = batch(a, t.log(), from, est, m.target(ceiling), m.minBatch(ceiling));
                if (!batch.isEmpty()) {
                    changed = true;
                    view = renderer.render(t.log(), a.state);
                    long after = estimate(a, view, t.prefix());
                    event(t, "mask", batch.size(), est - after, after);
                    est = after;
                }
            }
            if (est + config.maxTokens() > ceiling && switchSegment(a, t, view, from)) {
                changed = true;
                view = renderer.render(t.log(), a.state);
                long after = estimate(a, view, t.prefix());
                event(t, "segment", 0, est - after, after);
                est = after;
            }
            if (est + config.maxTokens() > window) {
                List<Mask> em = planner.emergency(t.log(), a.state,
                        Math.round((est + config.maxTokens() - window) / a.ratio));
                if (!em.isEmpty()) {
                    a.state.masks.addAll(em);
                    changed = true;
                    view = renderer.render(t.log(), a.state);
                    long after = estimate(a, view, t.prefix());
                    event(t, "emergency", em.size(), est - after, after);
                }
            }
            if (changed) save(t.chatId(), t.agentKey(), a);
            a.lastChars = chars(view, t.prefix());
            return new ContextView(view, changed, false);
        }
    }

    @Override
    public ContextView onOverflow(ContextTurn t) {
        Agent a = agent(t.chatId(), t.agentKey(), t.main());
        synchronized (a) {
            a.log = t.log();
            validate(a.state, t.log());
            // the estimate was too low: trust it less from now on
            a.ratio = Math.min(2.0, Math.max(a.ratio, 1.0) * 1.3);
            int from = Math.max(t.inherited()[0], 0);
            ArrayNode view = renderer.render(t.log(), a.state);
            long est = estimate(a, view, t.prefix());
            String kind = "mask";
            int count;
            List<Mask> batch = batch(a, t.log(), from, est, Math.round(est * 0.6), 0);
            count = batch.size();
            if (batch.isEmpty()) {
                kind = "segment";
                count = 0;
                if (!switchSegment(a, t, view, from)) {
                    kind = "emergency";
                    List<Mask> em = planner.emergency(t.log(), a.state, 1);
                    if (em.isEmpty()) return null;
                    a.state.masks.addAll(em);
                    count = em.size();
                }
            }
            save(t.chatId(), t.agentKey(), a);
            view = renderer.render(t.log(), a.state);
            long after = estimate(a, view, t.prefix());
            event(t, kind, count, est - after, after);
            a.lastChars = chars(view, t.prefix());
            return new ContextView(view, true, false);
        }
    }

    @Override
    public CompactResult compactNow(ContextTurn t, String focus) {
        long size = contextTokens(t.chatId(), t.agentKey(), t.log());
        return new CompactResult(false, size, size, 0, NOT_USED);
    }

    @Override
    public long contextTokens(String chatId, String agentKey, ArrayNode log) {
        Agent a = agent(chatId, agentKey, true);
        synchronized (a) {
            validate(a.state, log);
            return Math.round(Messages.tokens(renderer.render(log, a.state)) * a.ratio);
        }
    }

    @Override
    public ArrayNode inheritedView(String chatId, String agentKey, ArrayNode log) {
        Agent a = agent(chatId, agentKey, true);
        synchronized (a) {
            validate(a.state, log);
            return renderer.render(log, a.state);
        }
    }

    @Override
    public void release(String chatId, String agentKey) {
        agents.remove(key(chatId, agentKey));
    }

    @Override
    public void forget(String chatId) {
        agents.keySet().removeIf(k -> k.startsWith(chatId + "\n"));
        store.delete(chatId);
    }

    /** The decisions of an agent (tests, recall). */
    MnemonicState state(String chatId, String agentKey) {
        return agent(chatId, agentKey, true).state;
    }

    /** The agent whose tool call this is: by key, else the first known agent of the chat. */
    Agent agentFor(String chatId, String agentKey) {
        if (agentKey != null) {
            Agent a = agents.get(key(chatId, agentKey));
            if (a != null) return a;
        }
        for (Map.Entry<String, Agent> e : agents.entrySet()) {
            if (e.getKey().startsWith(chatId + "\n") && e.getValue().persistent) return e.getValue();
        }
        return null;
    }

    private Agent agent(String chatId, String agentKey, boolean persistent) {
        return agents.computeIfAbsent(key(chatId, agentKey),
                k -> new Agent(persistent ? store.load(chatId, agentKey) : new MnemonicState(), persistent));
    }

    private void save(String chatId, String agentKey, Agent a) {
        if (!a.persistent) return;
        try {
            store.save(chatId, agentKey, a.state);
        } catch (RuntimeException e) {
            log.warn("Cannot save the mnemonic state of chat {}: {}", chatId, e.getMessage());
        }
    }

    private List<Mask> batch(Agent a, ArrayNode log, int from, long est, long target, long minBatch) {
        MnemonicState st = a.state;
        int start = Math.max(from, Math.max(st.frontier, st.start()));
        List<JsonNode> applied = renderer.applied(log, st, start);
        // the planner works on unscaled estimates
        List<Mask> batch = planner.batch(log, st, start, applied, start, Math.round(est / a.ratio),
                Math.round(target / a.ratio), Math.round(minBatch / a.ratio));
        if (batch.isEmpty()) return batch;
        st.masks.addAll(batch);
        st.frontier = Math.max(st.frontier, batch.get(0).index());
        metrics.context("mask");
        return batch;
    }

    private boolean switchSegment(Agent a, ContextTurn t, ArrayNode view, int from) {
        MnemonicState st = a.state;
        int start = Math.max(from, st.start());
        List<JsonNode> applied = renderer.applied(t.log(), st, start);
        long tailMax = Math.round(config.mnemonic().tailMax(config.contextBudgetTokens()) / a.ratio);
        int cut = SegmentSwitcher.chooseCut(t.log(), start, applied, tailMax);
        if (cut <= 0) return false;
        String notes;
        try {
            notes = switcher.notes(t.prefix(), view, config.mnemonic().notesMaxTokens(), t.usageSink());
        } catch (RuntimeException e) {
            log.warn("Handoff notes failed: {}", e.getMessage());
            t.sink().emit("warning", mapper.createObjectNode()
                    .put("message", "Handoff notes failed: " + e.getMessage()));
            notes = "(handoff notes could not be written; use history_recall)";
        }
        String index = SegmentSwitcher.index(t.log(), st, cut, config.mnemonic().indexMaxTokens());
        st.segments.add(SegmentSwitcher.segment(t.log(), cut, notes, index));
        st.frontier = Math.max(st.frontier, cut);
        metrics.context("segment");
        return true;
    }

    /** Drops decisions about messages that changed since. @return true if any was dropped */
    private static boolean validate(MnemonicState st, ArrayNode log) {
        int bad = Integer.MAX_VALUE;
        for (Mask m : st.masks) {
            if (m.index() >= log.size() || !Messages.hash(m.index(), log.get(m.index())).equals(m.hash())) {
                bad = Math.min(bad, m.index());
            }
        }
        for (Segment s : st.segments) {
            if (s.cut() >= log.size() || !Messages.hash(s.cut(), log.get(s.cut())).equals(s.cutHash())) {
                bad = Math.min(bad, s.cut());
            }
            if (s.firstUser() >= 0 && (s.firstUser() >= log.size()
                    || !Messages.hash(s.firstUser(), log.get(s.firstUser())).equals(s.firstUserHash()))) {
                bad = Math.min(bad, s.firstUser());
            }
        }
        if (st.frontier > log.size()) bad = Math.min(bad, log.size());
        return bad != Integer.MAX_VALUE && st.invalidateFrom(bad);
    }

    /** Learns how far the chars/3.5 estimate is from the measured prompt; a ratio outside 0.5..2 is not comparable. */
    private static void calibrate(Agent a, long measured) {
        if (measured <= 0 || a.lastChars <= 0) return;
        double r = measured / (a.lastChars / Messages.CHARS_PER_TOKEN);
        if (r >= 0.5 && r <= 2.0) a.ratio = r;
    }

    private static long estimate(Agent a, ArrayNode view, RequestPrefix prefix) {
        return Math.round(Math.ceil(chars(view, prefix) / Messages.CHARS_PER_TOKEN) * a.ratio);
    }

    private static long chars(ArrayNode view, RequestPrefix prefix) {
        long c = view.toString().length();
        if (prefix != null) {
            c += prefix.pinned() == null ? 0 : prefix.pinned().length();
            c += prefix.extra() == null ? 0 : prefix.extra().length();
            if (prefix.tools() != null) {
                for (HarnessTool tool : prefix.tools()) {
                    c += tool.name().length() + tool.description().length() + tool.inputSchema().toString().length();
                }
            }
        }
        return c;
    }

    private void event(ContextTurn t, String kind, int count, long saved, long after) {
        t.sink().emit("compaction", mapper.createObjectNode().put("strategy", "mnemonic").put("kind", kind)
                .put("masked", count).put("savedTokens", saved).put("estimatedTokens", after));
    }

    private static String key(String chatId, String agentKey) {
        return chatId + "\n" + agentKey;
    }

    /** Messages that recall may return: masked, or moved out of the prompt by a segment switch. */
    static java.util.function.IntPredicate searchable(MnemonicState st) {
        int start = st.start();
        List<Integer> masked = new ArrayList<>();
        for (Mask m : st.masks) masked.add(m.index());
        return i -> i < start || masked.contains(i);
    }
}
