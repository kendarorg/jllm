package org.kendar.llm.harness.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * OAuth tokens by {@code (principalId, name)}. {@code principalId} null is the single user, and the sources shared by
 * every user ({@code perUser: false}). Names are {@code api:<name>}, {@code mcp:<server>} or a provider login name.
 * <p>
 * A store only keeps tokens: it never refreshes them (the harness runs logins and refreshes, see
 * {@link #compareAndPut}). Implementations never log token contents.
 */
public interface TokenStore {

    Optional<OAuthToken> get(String principalId, String name);

    void put(String principalId, String name, OAuthToken token);

    /** @return true when there was a token */
    boolean remove(String principalId, String name);

    /** The names with a token for this principal, sorted. */
    List<String> names(String principalId);

    /** All tokens of a user (user deleted). */
    default void removeAll(String principalId) {
        names(principalId).forEach(n -> remove(principalId, n));
    }

    /**
     * Writes {@code next} (null: removes the entry) only if the stored token is still {@code expected} (null: no
     * token stored). Refreshers of
     * rotated refresh tokens use it so that only one of two concurrent refreshes is kept.
     *
     * @return false when another writer changed the entry first
     */
    default boolean compareAndPut(String principalId, String name, OAuthToken expected, OAuthToken next) {
        return locked(principalId, name, () -> {
            OAuthToken cur = get(principalId, name).orElse(null);
            if (!Objects.equals(cur, expected)) return false;
            if (next == null) remove(principalId, name);
            else put(principalId, name, next);
            return true;
        });
    }

    /** Runs {@code action} holding the lock of one entry. Default: an in-process lock per (principal, name). */
    default <T> T locked(String principalId, String name, Supplier<T> action) {
        ReentrantLock lock = Locks.LOCKS.computeIfAbsent(new Locks.Key(this, principalId, name),
                k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** True when {@link #locked} also excludes other processes (file lock, CAS on a server). */
    default boolean crossProcess() {
        return false;
    }

    /** For {@code /login} and logs: "file ~/.jllm/auth.json", "macOS keychain", ... Never a secret. */
    String describe();

    /** The default store of a single-user setup: a {@link FileTokenStore} on {@code ~/.jllm/auth.json}. */
    static TokenStore defaultStore() {
        return FileTokenStore.defaultStore();
    }

    /** The in-process locks of the default {@link #locked}. */
    final class Locks {
        private record Key(TokenStore store, String principal, String name) {
            @Override
            public boolean equals(Object o) {
                return o instanceof Key k && k.store == store && Objects.equals(k.principal, principal)
                        && k.name.equals(name);
            }

            @Override
            public int hashCode() {
                return System.identityHashCode(store) * 31 + Objects.hashCode(principal) * 17 + name.hashCode();
            }
        }

        private static final Map<Key, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

        private Locks() {
        }
    }
}
