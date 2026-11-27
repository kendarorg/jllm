package org.kendar.llm.web.commons.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.settings.SettingsSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Where the role mapping options come from: the section {@value #SECTION} of the stored settings when an administrator
 * saved one, else the {@code jllm.auth.oidc.role-mapping.*} properties. The section is a host one
 * ({@link SettingsSink#HOST_PREFIX}): the harness never sees it. Every read goes through the sink's snapshot, so a
 * change applies at the next sign-in.
 */
public final class RoleMappingSettings {

    private static final Logger log = LoggerFactory.getLogger(RoleMappingSettings.class);
    public static final String SECTION = SettingsSink.HOST_PREFIX + "roleMapping";

    private final RoleMappingOptions initial;
    private final SettingsSink sink;
    private final String defaultRole;

    /**
     * @param initial     the properties
     * @param sink        the stored settings; null = the properties only, not editable
     * @param defaultRole {@code jllm.auth.oidc.default-role}, or null
     * @throws IllegalStateException when the properties are invalid, naming the key
     */
    public RoleMappingSettings(RoleMappingProperties initial, SettingsSink sink, String defaultRole) {
        this.initial = RoleMappingOptions.of(initial);
        this.sink = sink;
        this.defaultRole = defaultRole == null || defaultRole.isBlank() ? null : defaultRole.strip();
        if (this.initial.enabled() && this.initial.onNoMatch() == RoleMappingOptions.OnNoMatch.DEFAULT
                && this.defaultRole == null) {
            throw new IllegalStateException("jllm.auth.oidc.default-role is required when "
                    + "jllm.auth.oidc.role-mapping.enabled is true and on-no-match is default");
        }
    }

    /** The options in force: the stored ones, else the properties. A stored value that cannot be read is logged and skipped. */
    public RoleMappingOptions current() {
        Optional<JsonNode> stored = sink == null ? Optional.empty() : sink.get(SECTION);
        if (stored.isEmpty()) return initial;
        try {
            return RoleMappingOptions.parse(stored.get(), initial);
        } catch (IllegalArgumentException e) {
            log.warn("Stored role mapping options ignored ({}): {}", sink.origin(), e.getMessage());
            return initial;
        }
    }

    /** The options of the properties. */
    public RoleMappingOptions initial() {
        return initial;
    }

    /** Whether the options in force are stored ones. */
    public boolean stored() {
        return sink != null && sink.get(SECTION).isPresent();
    }

    /** Whether the options can be changed at runtime (there is a settings sink). */
    public boolean editable() {
        return sink != null;
    }

    public String defaultRole() {
        return defaultRole;
    }

    /**
     * Stores new options; a key absent from {@code json} keeps its current value.
     *
     * @throws IllegalArgumentException for invalid options
     * @throws IllegalStateException    when there is no settings sink
     */
    public RoleMappingOptions put(JsonNode json) {
        if (sink == null) throw new IllegalStateException("The role mapping options are read-only: no settings store");
        RoleMappingOptions next = RoleMappingOptions.parse(json, current());
        if (next.enabled() && next.onNoMatch() == RoleMappingOptions.OnNoMatch.DEFAULT && defaultRole == null) {
            throw new IllegalArgumentException("'onNoMatch' default needs jllm.auth.oidc.default-role: set it, or use "
                    + "keep or refuse");
        }
        sink.put(SECTION, next.toJson());
        return next;
    }

    /** Drops the stored options: the properties apply again. @return whether there were stored ones */
    public boolean reset() {
        if (sink == null) throw new IllegalStateException("The role mapping options are read-only: no settings store");
        return sink.remove(SECTION);
    }
}
