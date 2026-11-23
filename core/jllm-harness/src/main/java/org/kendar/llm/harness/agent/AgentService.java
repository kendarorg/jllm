package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.llm.CancelToken;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Owns one run per chat at a time, buffers its events (replayable by seq) and supports cancellation. */
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int MAX_BUFFERED_EVENTS = 2000;
    /** Events one subscriber may have waiting: a full replay plus live ones; beyond it the subscriber is dropped. */
    static final int SUBSCRIBER_QUEUE = MAX_BUFFERED_EVENTS + 2048;
    /** Last event of a subscriber that fell behind and was dropped: it reconnects and replays from its last seq. */
    public static final String DROPPED_EVENT = "subscriber_dropped";
    /**
     * Delivered live with seq 0, never buffered nor replayed. Streaming deltas, so a reconnecting client is not
     * replayed thousands of pieces (it gets the final {@code text} event instead), and {@code content_warning}: a
     * message to the user about a file just loaded, never kept anywhere.
     */
    public static final java.util.Set<String> TRANSIENT_EVENTS = java.util.Set.of("text_delta", "thinking_delta",
            "text_reset", org.kendar.llm.harness.safety.ContentWarnings.EVENT);

    public static class RunAlreadyActiveException extends RuntimeException {
        public RunAlreadyActiveException(String chatId) {
            super("Chat " + chatId + " already has a run in progress");
        }
    }

    private static final class ChatRun {
        final ArrayDeque<AgentEvent> events = new ArrayDeque<>();
        /** Guards {@link #events} and {@link #nextSeq}. */
        final ReentrantLock eventsLock = new ReentrantLock();
        /** Guards the run state (running, closing, queues); {@link #changed} is signalled when a run ends or closes. */
        final ReentrantLock lock = new ReentrantLock();
        final Condition changed = lock.newCondition();
        final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
        long nextSeq = 1;
        volatile boolean running;
        volatile boolean cancelRequested;
        /** The run has taken its last decision to finish; a new message must wait for it to end. */
        volatile boolean closing;
        volatile CancelToken token = new CancelToken();
        final java.util.concurrent.ConcurrentLinkedQueue<String> steering = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.ConcurrentLinkedQueue<String> followUp = new java.util.concurrent.ConcurrentLinkedQueue<>();
    }

    private final AgentLoop loop;
    private final ChatSessionService sessions;
    private final ApprovalService approvals;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ChatRun> runs = new ConcurrentHashMap<>();

    private final boolean serializeRuns;
    private final Semaphore runSlot;
    /** Called when a user message starts a turn (the first one of a run, a queued one, a steer or a follow-up). */
    private volatile Consumer<String> turnStart = chatId -> { };

    /** Sets what runs at the start of every user turn, e.g. opening a checkpoint for {@code /undo}. */
    public void onTurnStart(Consumer<String> turnStart) {
        this.turnStart = turnStart == null ? chatId -> { } : turnStart;
    }

    public AgentService(AgentLoop loop, ChatSessionService sessions, ApprovalService approvals, ObjectMapper mapper,
                        HarnessConfig config) {
        this(loop, sessions, approvals, mapper, config, 1);
    }

    /** @param maxParallel chat runs allowed at once while {@code serializeRuns} is on ({@code -1} unlimited, 0 means 1) */
    public AgentService(AgentLoop loop, ChatSessionService sessions, ApprovalService approvals, ObjectMapper mapper,
                        HarnessConfig config, int maxParallel) {
        this.runSlot = new Semaphore(maxParallel < 0 ? Integer.MAX_VALUE : Math.max(1, maxParallel), true);
        this.loop = loop;
        this.sessions = sessions;
        this.approvals = approvals;
        this.mapper = mapper;
        this.serializeRuns = config.serializeRuns();
    }

    /** Starts a user turn asynchronously. @throws RunAlreadyActiveException if the chat is busy */
    public void start(String chatId, String userText) {
        sessions.get(chatId); // fail fast on unknown chat
        ChatRun run = runs.computeIfAbsent(chatId, k -> new ChatRun());
        run.lock.lock();
        try {
            if (run.running) throw new RunAlreadyActiveException(chatId);
            run.running = true;
            run.closing = false;
            run.cancelRequested = false;
            run.token = new CancelToken();
            run.steering.clear();
            run.followUp.clear();
        } finally {
            run.lock.unlock();
        }
        // Events of earlier runs are already part of the persisted history; replaying them duplicates the chat
        run.eventsLock.lock();
        try {
            run.events.clear();
        } finally {
            run.eventsLock.unlock();
        }
        executor.submit(() -> execute(chatId, run, userText));
    }

    /**
     * Queues a steering message for the active run: it is applied after the current round of tool calls (or as the next
     * turn when the answer in progress is final). Without an active run it starts one, like {@link #start}.
     */
    public void steer(String chatId, String text) {
        enqueue(chatId, text, true);
    }

    /** Queues a message that is applied only when the agent would otherwise finish. Starts a run when none is active. */
    public void followUp(String chatId, String text) {
        enqueue(chatId, text, false);
    }

    private void enqueue(String chatId, String text, boolean steering) {
        sessions.get(chatId);
        ChatRun run = runs.computeIfAbsent(chatId, k -> new ChatRun());
        long remaining = TimeUnit.SECONDS.toNanos(5);
        run.lock.lock();
        try {
            while (true) {
                if (run.running && !run.closing) {
                    (steering ? run.steering : run.followUp).add(text);
                    publish(run, "steer_queued", mapper.createObjectNode().put("text", text)
                            .put("kind", steering ? "steer" : "followup"));
                    return;
                }
                if (!run.running) {
                    start(chatId, text);
                    return;
                }
                if (remaining <= 0) throw new RunAlreadyActiveException(chatId);
                try {
                    remaining = run.changed.awaitNanos(remaining); // the run is finishing: wait for it, then start anew
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RunAlreadyActiveException(chatId);
                }
            }
        } finally {
            run.lock.unlock();
        }
    }

    private void execute(String chatId, ChatRun run, String userText) {
        EventSink sink = (type, data) -> publish(run, type, data);
        sink.emit("run_started", mapper.createObjectNode().put("text", userText));
        boolean locked = false;
        AgentLoop.Outcome outcome = AgentLoop.Outcome.FAILED;
        try {
            locked = acquireRunSlot(chatId, run, sink);
            if (run.token.isCancelled()) return;
            maybeTitle(chatId, userText);
            AgentLoop.Inbox inbox = new AgentLoop.Inbox() {
                @Override
                public String pollSteering() {
                    return turnStarted(chatId, sink, run.steering.poll());
                }

                @Override
                public String pollFollowUp() {
                    return turnStarted(chatId, sink, run.followUp.poll());
                }
            };
            turnStarted(chatId, sink, userText);
            outcome = loop.run(chatId, userText, sink, run.token, inbox);
            // a message queued just as the loop finished starts the next turn instead of being lost
            while (!run.token.isCancelled()) {
                String next;
                run.lock.lock();
                try {
                    next = run.steering.poll();
                    if (next == null) next = run.followUp.poll();
                    if (next == null) {
                        run.closing = true;
                        run.changed.signalAll();
                        break;
                    }
                } finally {
                    run.lock.unlock();
                }
                sink.emit("steer_applied", mapper.createObjectNode().put("text", next));
                turnStarted(chatId, sink, next);
                outcome = loop.run(chatId, next, sink, run.token, inbox);
            }
        } catch (Throwable t) { // boundary: run thread top level: any failure ends the run as FAILED
            log.error("Agent run failed for chat {}", chatId, t);
            outcome = AgentLoop.Outcome.FAILED;
            sink.emit("error", mapper.createObjectNode().put("message", String.valueOf(t.getMessage())));
        } finally {
            if (locked) runSlot.release();
            // not running any more before anyone hears run_finished (a client may start the next run at once); under
            // the run lock, so a new run's start() cannot clear the buffer between the two
            run.lock.lock();
            try {
                run.running = false;
                // outcome: how the last turn ended (completed, stuck, unverified, max_turns, ...), for embedders
                publish(run, "run_finished", mapper.createObjectNode().put("cancelled", run.cancelRequested)
                        .put("outcome", (run.cancelRequested ? AgentLoop.Outcome.CANCELLED : outcome).name()
                                .toLowerCase(java.util.Locale.ROOT)));
                run.changed.signalAll();
            } finally {
                run.lock.unlock();
            }
        }
    }

    /** Runs the turn-start callback for a user message that is about to be applied; passes the message through. */
    private String turnStarted(String chatId, EventSink sink, String text) {
        if (text == null) return null;
        try {
            turnStart.accept(chatId);
        } catch (RuntimeException e) { // boundary: a failed turn start is reported to the client
            log.warn("Turn start failed for chat {}: {}", chatId, e.getMessage());
            sink.emit("warning", mapper.createObjectNode()
                    .put("message", "Turn start failed (/undo may not see this turn): " + e.getMessage()));
        }
        return text;
    }

    /** Waits for the single run slot when serialization is on; returns whether a permit is held. */
    private boolean acquireRunSlot(String chatId, ChatRun run, EventSink sink) throws InterruptedException {
        if (!serializeRuns) return false;
        if (runSlot.tryAcquire()) return true;
        log.info("Chat {} queued behind another agent run", chatId);
        sink.emit("run_queued", mapper.createObjectNode());
        while (!run.token.isCancelled()) {
            if (runSlot.tryAcquire(500, TimeUnit.MILLISECONDS)) return true;
        }
        return false;
    }

    private void maybeTitle(String chatId, String userText) {
        ChatSessionData s = sessions.get(chatId);
        if ("New chat".equals(s.title())) {
            String t = userText.strip().replaceAll("\\s+", " ");
            sessions.setTitle(chatId, t.length() > 60 ? t.substring(0, 60) + "…" : t);
        }
    }

    /**
     * Buffers the event and delivers it. Publishers on different threads may deliver out of seq order: each
     * subscriber's {@link OrderedListener} puts them back in order.
     */
    private void publish(ChatRun run, String type, JsonNode data) {
        AgentEvent ev;
        List<Consumer<AgentEvent>> targets;
        if (TRANSIENT_EVENTS.contains(type)) {
            ev = new AgentEvent(0, type, data);
            for (Consumer<AgentEvent> l : run.listeners) {
                try {
                    if (l instanceof OrderedListener o) o.acceptTransient(ev);
                    else l.accept(ev);
                } catch (RuntimeException e) { // boundary: listener isolation: a failing listener is dropped
                    run.listeners.remove(l);
                }
            }
            return;
        }
        run.eventsLock.lock();
        try {
            ev = new AgentEvent(run.nextSeq++, type, data);
            run.events.addLast(ev);
            if (run.events.size() > MAX_BUFFERED_EVENTS) run.events.removeFirst();
            // the listeners registered when the seq was assigned: a later subscriber gets this event by replay
            targets = List.copyOf(run.listeners);
        } finally {
            run.eventsLock.unlock();
        }
        for (Consumer<AgentEvent> l : targets) {
            try {
                l.accept(ev);
            } catch (RuntimeException e) { // boundary: listener isolation: a failing listener is dropped
                run.listeners.remove(l);
            }
        }
    }

    /**
     * Delivers events to one subscriber in seq order, without gaps or duplicates: an event that arrives ahead of
     * its turn is held until the ones before it arrived.
     */
    static final class OrderedListener implements Consumer<AgentEvent> {
        private final Consumer<AgentEvent> target;
        private final java.util.TreeMap<Long, AgentEvent> held = new java.util.TreeMap<>();
        private final ReentrantLock lock = new ReentrantLock();
        private long next;

        /** @param target must not block: it only hands the event over (see {@link Subscriber}) */
        OrderedListener(Consumer<AgentEvent> target, long next) {
            this.target = target;
            this.next = next;
        }

        /** A streaming delta (seq 0): delivered at once, outside the seq order. */
        void acceptTransient(AgentEvent ev) {
            lock.lock();
            try {
                target.accept(ev);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void accept(AgentEvent ev) {
            lock.lock();
            try {
                if (ev.seq() < next) return; // already delivered
                held.put(ev.seq(), ev);
                // a seq that never arrives (should not happen) must not hold the stream forever
                if (held.size() > MAX_BUFFERED_EVENTS) next = held.firstKey();
                for (AgentEvent e = held.remove(next); e != null; e = held.remove(next)) {
                    next++;
                    target.accept(e);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * One subscriber's bounded queue, drained by its own virtual thread: a slow consumer (a stalled SSE write) delays
     * neither the run nor other subscribers. When the queue overflows the subscriber is detached and its queue is
     * replaced by a final {@link #DROPPED_EVENT}; it reconnects and replays from its last seq.
     */
    static final class Subscriber {
        private static final AgentEvent STOP = new AgentEvent(0, "stop", null);
        private final java.util.concurrent.LinkedBlockingQueue<AgentEvent> queue;
        private final Consumer<AgentEvent> target;
        private final JsonNode droppedData;
        private final Runnable detach;
        private volatile boolean finished;

        Subscriber(Consumer<AgentEvent> target, int capacity, JsonNode droppedData, Runnable detach) {
            this.target = target;
            this.queue = new java.util.concurrent.LinkedBlockingQueue<>(capacity);
            this.droppedData = droppedData;
            this.detach = detach;
            Thread.ofVirtual().name("event-subscriber").start(this::drain);
        }

        /** Never blocks. */
        void offer(AgentEvent ev) {
            if (finished) return;
            if (queue.offer(ev)) return;
            finished = true;
            queue.clear();
            queue.add(new AgentEvent(0, DROPPED_EVENT, droppedData));
            queue.offer(STOP);
            detach.run();
        }

        /** Unsubscribed: pending events are discarded. */
        void close() {
            finished = true;
            queue.clear();
            queue.offer(STOP);
        }

        private void drain() {
            try {
                while (true) {
                    AgentEvent ev = queue.take();
                    if (ev == STOP) return;
                    target.accept(ev);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) { // boundary: listener isolation: a failing listener is dropped
                finished = true;
                detach.run();
            }
        }
    }

    /**
     * Publishes an event to the chat's stream outside of a run (e.g. "a login finished"): live subscribers get it at
     * once, a reconnecting one by replay until the next run starts. Unknown chats are ignored.
     */
    public void notify(String chatId, String type, JsonNode data) {
        if (chatId == null || type == null || sessions.find(chatId).isEmpty()) return;
        ChatRun run = runs.computeIfAbsent(chatId, k -> new ChatRun());
        publish(run, type, data == null ? mapper.createObjectNode() : data);
    }

    /** Manual /compact. @throws RunAlreadyActiveException if a run is in progress */
    public CompactResult compact(String chatId, String focus) {
        sessions.get(chatId);
        ChatRun run = runs.computeIfAbsent(chatId, k -> new ChatRun());
        run.lock.lock();
        try {
            if (run.running) throw new RunAlreadyActiveException(chatId);
            run.running = true;
        } finally {
            run.lock.unlock();
        }
        try {
            CompactResult r = loop.compact(chatId, focus);
            if (r.changed()) {
                publish(run, "compaction", mapper.createObjectNode().put("tier", 2).put("manual", true)
                        .put("tokensBefore", r.tokensBefore()).put("estimatedTokens", r.tokensAfter()));
            }
            return r;
        } finally {
            run.lock.lock();
            try {
                run.running = false;
                run.changed.signalAll();
            } finally {
                run.lock.unlock();
            }
        }
    }

    public org.kendar.llm.harness.tools.ValidationMode validationMode(String chatId) {
        return loop.toolExecutor().validationMode(chatId);
    }

    public void validationMode(String chatId, org.kendar.llm.harness.tools.ValidationMode mode) {
        loop.toolExecutor().validationMode(chatId, mode);
    }

    /**
     * @param kill stop a running script (run_js) at once; otherwise it gets a grace period to clean up. A pending
     *             approval is dismissed as denied either way.
     */
    public void cancel(String chatId, boolean kill) {
        ChatRun run = runs.get(chatId);
        if (run == null || !run.running) return;
        run.cancelRequested = true;
        run.steering.clear();
        run.followUp.clear();
        if (kill) run.token.kill();
        else run.token.cancel();
        approvals.cancelAll(chatId);
    }

    public List<ActiveSubAgent> activeSubAgents(String chatId) {
        return loop.subAgents().activeSubAgents(chatId);
    }

    public boolean isRunning(String chatId) {
        ChatRun run = runs.get(chatId);
        return run != null && run.running;
    }

    /** Sequence number of the last event published for the chat (0 if none). */
    public long lastSeq(String chatId) {
        ChatRun run = runs.get(chatId);
        if (run == null) return 0;
        run.eventsLock.lock();
        try {
            return run.nextSeq - 1;
        } finally {
            run.eventsLock.unlock();
        }
    }

    /** Buffered events with seq greater than {@code afterSeq} (0 = everything buffered). */
    public List<AgentEvent> events(String chatId, long afterSeq) {
        ChatRun run = runs.get(chatId);
        if (run == null) return List.of();
        run.eventsLock.lock();
        try {
            return run.events.stream().filter(e -> e.seq() > afterSeq).toList();
        } finally {
            run.eventsLock.unlock();
        }
    }

    /**
     * Replays buffered events after {@code afterSeq}, then delivers live ones, without gaps or duplicates.
     * @return a handle that unsubscribes
     */
    public Runnable subscribe(String chatId, long afterSeq, Consumer<AgentEvent> listener) {
        ChatRun run = runs.computeIfAbsent(chatId, k -> new ChatRun());
        OrderedListener[] ordered = new OrderedListener[1];
        Subscriber sub = new Subscriber(listener, SUBSCRIBER_QUEUE,
                mapper.createObjectNode().put("message", "subscriber too slow: reconnect and replay"),
                () -> run.listeners.remove(ordered[0]));
        List<AgentEvent> replay;
        // registration and snapshot under the lock that assigns seqs: every event is either replayed or delivered live
        run.eventsLock.lock();
        try {
            long last = run.nextSeq - 1;
            // a seq ahead of ours comes from before a restart (seqs start again at 1): replay everything buffered
            long from = afterSeq > last ? 0 : Math.max(0, afterSeq);
            replay = run.events.stream().filter(e -> e.seq() > from).toList();
            ordered[0] = new OrderedListener(sub::offer, replay.isEmpty() ? run.nextSeq : replay.get(0).seq());
            run.listeners.add(ordered[0]);
        } finally {
            run.eventsLock.unlock();
        }
        for (AgentEvent ev : replay) ordered[0].accept(ev);
        return () -> {
            run.listeners.remove(ordered[0]);
            sub.close();
        };
    }

    public List<ApprovalService.PendingApproval> pendingApprovals(String chatId) {
        return approvals.pending(chatId);
    }

    /**
     * @param remember    allow the tool for the rest of the chat, sub-agents included
     * @param rememberDir allow file writes in the call's directory and below, for this chat */
    public boolean resolveApproval(String chatId, String approvalId, boolean approved, String edited,
                                   boolean remember, boolean rememberDir) {
        return approvals.resolve(chatId, approvalId, approved, edited, remember, rememberDir);
    }
}
