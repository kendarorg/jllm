package org.kendar.llm.harness.plugin;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a plugin publishes a service under an interface that both sides share, and another plugin looks it up.
 * The interface must be loaded by the host, not packaged in either plugin jar.
 */
public final class ServiceRegistry {
    private final ConcurrentHashMap<Class<?>, Object> services = new ConcurrentHashMap<>();

    /** @throws IllegalStateException when the type already has a service */
    public <T> void publish(Class<T> type, T service) {
        if (services.putIfAbsent(type, java.util.Objects.requireNonNull(service)) != null) {
            throw new IllegalStateException("A service for " + type.getName() + " is already published");
        }
    }

    public <T> Optional<T> lookup(Class<T> type) {
        return Optional.ofNullable(type.cast(services.get(type)));
    }

    public <T> void withdraw(Class<T> type) {
        services.remove(type);
    }
}
