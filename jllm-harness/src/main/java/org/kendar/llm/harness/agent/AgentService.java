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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owns one run per chat at a time, buffers its events (replayable by seq) and supports cancellation. */
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int MAX_BUFFERED_EVENTS = 2000;

    public static class RunAlreadyActiveException extends RuntimeException {
        public RunAlreadyActiveException(String chatId) {
            super("Chat " + chatId + " already has a run in progress");
        }
    }

    private static final class ChatRun {
        final List<AgentEvent> events = new ArrayList<>();
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
        synchronized (run) {
            if (run.running) throw new RunAlreadyActiveException(chatId);
            run.running = true;
            run.closing = false;
            run.cancelRequested = false;
            run.token = new CancelToken();
            run.steering.clear();
            run.followUp.clear();
        }
        // Events of earlier runs are already part of the persisted history; replaying them duplicates the chat
        synchronized (run.events) {
            run.events.clear();
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            synchronized (run) {
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
            }
            if (System.nanoTime() > deadline) throw new RunAlreadyActiveException(chatId);
            try {
                Thread.sleep(10); // the run is finishing: wait for it, then start a new one
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RunAlreadyActiveException(chatId);
            }
        }
    }

    private void execute(String chatId, ChatRun run, String userText) {
        EventSink sink = (type, data) -> publish(run, type, data);
        sink.emit("run_started", mapper.createObjectNode().put("text", userText));
        boolean locked = false;
        try {
            locked = acquireRunSlot(chatId, run, sink);
            if (run.token.isCancelled()) return;
            maybeTitle(chatId, userText);
            AgentLoop.Inbox inbox = new AgentLoop.Inbox() {
                @Override
                public String pollSteering() {
                    return run.steering.poll();
                }

                @Override
                public String pollFollowUp() {
                    return run.followUp.poll();
                }
            };
            loop.run(chatId, userText, sink, run.token, inbox);
            // a message queued just as the loop finished starts the next turn instead of being lost
            while (!run.token.isCancelled()) {
                String next;
                synchronized (run) {
                    next = run.steering.poll();
                    if (next == null) next = run.followUp.poll();
                    if (next == null) {
                        run.closing = true;
                        break;
                    }
                }
                sink.emit("steer_applied", mapper.createObjectNode().put("text", next));
                loop.run(chatId, next, sink, run.token, inbox);
            }
        } catch (Throwable t) {
            log.error("Agent run failed for chat {}", chatId, t);
            sink.emit("error", mapper.createObjectNode().put("message", String.valueOf(t.getMessage())));
        } finally {
            if (locked) runSlot.release();
            sink.emit("run_finished", mapper.createObjectNode().put("cancelled", run.cancelRequested));
            run.running = false;
        }
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

    private void publish(ChatRun run, String type, JsonNode data) {
        AgentEvent ev;
        synchronized (run.events) {
            ev = new AgentEvent(run.nextSeq++, type, data);
            run.events.add(ev);
            if (run.events.size() > MAX_BUFFERED_EVENTS) run.events.remove(0);
        }
        for (Consumer<AgentEvent> l : run.listeners) {
            try {
                l.accept(ev);
            } catch (RuntimeException e) {
                run.listeners.remove(l);
            }
        }
    }

    /** Manual /compact. @throws RunAlreadyActiveException if a run is in progress */
    public CompactResult compact(String chatId, String focus) {
        sessions.get(chatId);
        ChatRun run = runs.computeIfAbsent(chatId, k -> new ChatRun());
        synchronized (run) {
            if (run.running) throw new RunAlreadyActiveException(chatId);
            run.running = true;
        }
        try {
            return loop.compact(chatId, focus);
        } finally {
            run.running = false;
        }
    }

    public void cancel(String chatId) {
        ChatRun run = runs.get(chatId);
        if (run == null || !run.running) return;
        run.cancelRequested = true;
        run.steering.clear();
        run.followUp.clear();
        run.token.cancel();
        approvals.cancelAll(chatId);
    }

    public boolean isRunning(String chatId) {
        ChatRun run = runs.get(chatId);
        return run != null && run.running;
    }

    /** Sequence number of the last event published for the chat (0 if none). */
    public long lastSeq(String chatId) {
        ChatRun run = runs.get(chatId);
        if (run == null) return 0;
        synchronized (run.events) {
            return run.nextSeq - 1;
        }
    }

    /** Buffered events with seq greater than {@code afterSeq} (0 = everything buffered). */
    public List<AgentEvent> events(String chatId, long afterSeq) {
        ChatRun run = runs.get(chatId);
        if (run == null) return List.of();
        synchronized (run.events) {
            return run.events.stream().filter(e -> e.seq() > afterSeq).toList();
        }
    }

    /**
     * Replays buffered events after {@code afterSeq}, then delivers live ones, without gaps or duplicates.
     * @return a handle that unsubscribes
     */
    public Runnable subscribe(String chatId, long afterSeq, Consumer<AgentEvent> listener) {
        ChatRun run = runs.computeIfAbsent(chatId, k -> new ChatRun());
        long[] highest = {afterSeq};
        Consumer<AgentEvent> ordered = ev -> {
            synchronized (highest) {
                if (ev.seq() > highest[0]) {
                    highest[0] = ev.seq();
                    listener.accept(ev);
                }
            }
        };
        run.listeners.add(ordered);
        for (AgentEvent ev : events(chatId, afterSeq)) ordered.accept(ev);
        return () -> run.listeners.remove(ordered);
    }

    public List<ApprovalService.PendingApproval> pendingApprovals(String chatId) {
        return approvals.pending(chatId);
    }

    public boolean resolveApproval(String chatId, String approvalId, boolean approved, String edited) {
        return approvals.resolve(chatId, approvalId, approved, edited);
    }

    public boolean resolveApproval(String chatId, String approvalId, boolean approved, String edited,
                                   boolean remember) {
        return approvals.resolve(chatId, approvalId, approved, edited, remember);
    }

    /** @param rememberDir allow file writes in the call's directory and below, for this chat */
    public boolean resolveApproval(String chatId, String approvalId, boolean approved, String edited,
                                   boolean remember, boolean rememberDir) {
        return approvals.resolve(chatId, approvalId, approved, edited, remember, rememberDir);
    }
}
