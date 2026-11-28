package org.kendar.llm.harness.sandbox;

import java.util.Locale;

/**
 * Which runner a role gets: nothing at all, confined processes on the host ({@link SharedLocalSandbox}), or one of two
 * kinds of container. Plain unconfined host processes are not a role kind: only the CLI runs those.
 */
public enum SandboxKind {
    /** No file access and no shell: every such tool call fails with {@link SandboxDisabledException}. The default. */
    NONE,
    /** Processes on the host in the chat directory with a minimal environment; bwrap-confined on Linux. */
    SHARED_LOCAL,
    /** A container with the hardened profile: no network unless granted, resource limits. Files and shell inside it. */
    DOCKER_ISOLATED,
    /** A container with the standard profile: network, no hardening. Files and shell inside it. */
    DOCKER_OPEN;

    public boolean docker() {
        return this == DOCKER_ISOLATED || this == DOCKER_OPEN;
    }

    /** The container profile of a docker kind ({@code hardened} or {@code standard}); null for the others. */
    public String profile() {
        return this == DOCKER_ISOLATED ? "hardened" : this == DOCKER_OPEN ? "standard" : null;
    }

    /** {@code none}, {@code shared-local}, {@code docker-isolated}, {@code docker-open}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** @param value one of the {@link #wire()} values (any case); null or blank: {@link #NONE} */
    public static SandboxKind parse(String value) {
        if (value == null || value.isBlank()) return NONE;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sandbox kind must be none, shared-local, docker-isolated or docker-open: " + value);
        }
    }
}
