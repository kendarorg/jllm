package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.llm.StreamListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Turns the deltas of a streamed response into {@code text_delta} / {@code thinking_delta} events, buffered so a long
 * answer is not one event per token: a buffer is flushed when it reaches {@value #MAX_CHARS} characters or
 * {@value #MAX_MILLIS} ms after the last flush, and by {@link #flush()} at the end of the call. Tool-argument deltas
 * are not emitted. A reset ({@code text_reset}) drops what is buffered.
 */
final class DeltaCoalescer implements StreamListener {

    static final int MAX_CHARS = 200;
    static final long MAX_MILLIS = 50;

    private record Key(String type, int block) {
    }

    private final EventSink sink;
    private final ObjectMapper mapper;
    private final String agent;
    private final LongSupplier clock;
    private final Map<Key, StringBuilder> buffers = new LinkedHashMap<>();
    private long lastFlush;

    /** @param agent sub-agent label, null for the main agent */
    DeltaCoalescer(EventSink sink, ObjectMapper mapper, String agent) {
        this(sink, mapper, agent, () -> System.nanoTime() / 1_000_000L);
    }

    DeltaCoalescer(EventSink sink, ObjectMapper mapper, String agent, LongSupplier clockMillis) {
        this.sink = sink;
        this.mapper = mapper;
        this.agent = agent;
        this.clock = clockMillis;
        this.lastFlush = clockMillis.getAsLong();
    }

    @Override
    public synchronized void onText(int block, String delta) {
        add("text_delta", block, delta);
    }

    @Override
    public synchronized void onThinking(int block, String delta) {
        add("thinking_delta", block, delta);
    }

    @Override
    public synchronized void onToolCall(int block, String id, String name, String argsDelta) {
        // not shown; time still passes for the text buffered before the call
        if (clock.getAsLong() - lastFlush >= MAX_MILLIS) flush();
    }

    @Override
    public synchronized void onReset() {
        buffers.clear();
        ObjectNode ev = mapper.createObjectNode();
        if (agent != null) ev.put("agent", agent);
        sink.emit("text_reset", ev);
    }

    private void add(String type, int block, String delta) {
        if (delta == null || delta.isEmpty()) return;
        StringBuilder b = buffers.computeIfAbsent(new Key(type, block), k -> new StringBuilder());
        b.append(delta);
        if (b.length() >= MAX_CHARS || clock.getAsLong() - lastFlush >= MAX_MILLIS) flush();
    }

    /** Emits what is buffered, in arrival order of the blocks. */
    synchronized void flush() {
        for (var e : buffers.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            ObjectNode ev = mapper.createObjectNode().put("block", e.getKey().block()).put("text", e.getValue().toString());
            if (agent != null) ev.put("agent", agent);
            sink.emit(e.getKey().type(), ev);
        }
        buffers.clear();
        lastFlush = clock.getAsLong();
    }
}
