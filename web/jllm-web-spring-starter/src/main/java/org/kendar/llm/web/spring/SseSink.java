package org.kendar.llm.web.spring;

import org.kendar.llm.web.commons.EventSink;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** An {@link EventSink} writing to an {@link SseEmitter}. */
final class SseSink implements EventSink {

    private final SseEmitter emitter;
    private final List<Runnable> closeHooks = new ArrayList<>();
    private final List<Runnable> timeoutHooks = new ArrayList<>();
    private boolean closed;

    SseSink(long timeoutMs) {
        emitter = new SseEmitter(timeoutMs);
        emitter.onCompletion(this::closed);
        emitter.onError(e -> closed());
        emitter.onTimeout(() -> {
            run(timeoutHooks);
            complete();
        });
    }

    SseEmitter emitter() {
        return emitter;
    }

    @Override
    public void send(String id, String event, String data) throws IOException {
        SseEmitter.SseEventBuilder sse = SseEmitter.event();
        if (id != null) sse.id(id);
        emitter.send(sse.name(event).data(data));
    }

    @Override
    public void comment(String text) throws IOException {
        emitter.send(SseEmitter.event().comment(text));
    }

    @Override
    public void complete() {
        closed();
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // already completed
        }
    }

    @Override
    public void completeWithError(Throwable error) {
        closed();
        try {
            emitter.completeWithError(error);
        } catch (IllegalStateException ignored) {
            // already completed
        }
    }

    @Override
    public void onClose(Runnable action) {
        boolean now;
        synchronized (this) {
            now = closed;
            if (!now) closeHooks.add(action);
        }
        if (now) action.run();
    }

    @Override
    public void onTimeout(Runnable action) {
        synchronized (this) {
            timeoutHooks.add(action);
        }
    }

    private void closed() {
        List<Runnable> hooks;
        synchronized (this) {
            if (closed) return;
            closed = true;
            hooks = new ArrayList<>(closeHooks);
            closeHooks.clear();
        }
        hooks.forEach(Runnable::run);
    }

    private void run(List<Runnable> hooks) {
        List<Runnable> copy;
        synchronized (this) {
            copy = new ArrayList<>(hooks);
        }
        copy.forEach(Runnable::run);
    }
}
