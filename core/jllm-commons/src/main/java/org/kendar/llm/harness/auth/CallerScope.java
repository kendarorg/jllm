package org.kendar.llm.harness.auth;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The user a model call runs for, for the current thread. Set at the roots (the agent loop of a chat, a flow run's
 * model call) and read once by each model client at the entry of a call. A call with no scope is a call with no user
 * (startup probes): it uses the global key only, never another user's credential.
 * <p>
 * A {@link ThreadLocal}, not an inheritable one: {@code InheritableThreadLocal} does not cross the pool of
 * {@code CompletableFuture.supplyAsync}. Hops to other threads capture the scope on the submitting thread with
 * {@link #wrap} and restore it on the worker.
 */
public final class CallerScope {

    /**
     * The caller of a call.
     *
     * @param principal the user; null is the single local user (CLI)
     */
    public record Caller(HarnessPrincipal principal) {
    }

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    private CallerScope() {
    }

    /** The caller of this thread; null when there is no scope (a call with no user). */
    public static Caller current() {
        return CURRENT.get();
    }

    /** Runs {@code body} as {@code caller} (null: no scope) and restores the previous scope. */
    public static <T> T call(Caller caller, Supplier<T> body) {
        Caller previous = CURRENT.get();
        set(caller);
        try {
            return body.get();
        } finally {
            set(previous);
        }
    }

    /** Runs {@code body} as the user {@code principal} (null: the single local user). */
    public static <T> T callAs(HarnessPrincipal principal, Supplier<T> body) {
        return call(new Caller(principal), body);
    }

    public static void runAs(HarnessPrincipal principal, Runnable body) {
        callAs(principal, () -> {
            body.run();
            return null;
        });
    }

    private static void set(Caller c) {
        if (c == null) CURRENT.remove();
        else CURRENT.set(c);
    }

    /** The task, running with the scope of the thread that calls {@code wrap}. */
    public static Runnable wrap(Runnable task) {
        Caller c = CURRENT.get();
        return () -> call(c, () -> {
            task.run();
            return null;
        });
    }

    public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
        Caller c = CURRENT.get();
        return () -> call(c, task);
    }

    public static <T> Callable<T> wrapCallable(Callable<T> task) {
        Caller c = CURRENT.get();
        return () -> {
            Caller previous = CURRENT.get();
            set(c);
            try {
                return task.call();
            } finally {
                set(previous);
            }
        };
    }
}
