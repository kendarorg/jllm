package org.kendar.llm.harness.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.permission.ToolPermission;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.harness.util.Wildcard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A role. The {@code admin} role always has every tool; its approval is the only thing that can be set.
 *
 * @param tools    tool name patterns ({@code *} = any run of characters); ["*"] = all
 * @param readOnly only READ_ONLY tools
 * @param approval {@code off}: no approval prompts; {@code ask} (default): the harness policy
 * @param toolPermissions tool name pattern ({@code *} = any run of characters) to {@code allow} (runs unattended,
 *                        no question ever), {@code ask} or {@code deny}; deny wins over ask over allow, then the
 *                        approval above applies
 * @param settings        free JSON per feature, top key = feature ({@code sandbox}, {@code idp}, ...): unknown keys are
 *                        kept as they are. Keys: 1-64 of a-z, 0-9, '_' and '-', not one of the role's own fields;
 *                        at most {@value #MAX_SETTINGS_BYTES} bytes as JSON and {@value #MAX_SETTINGS_DEPTH} levels deep
 */
public record Role(String name, List<String> tools, boolean readOnly, String approval,
                   Map<String, ToolPermission> toolPermissions, Map<String, JsonNode> settings) {

    /** Largest settings map, as compact JSON. */
    public static final int MAX_SETTINGS_BYTES = 64 * 1024;
    /** Deepest nesting of a settings value (the value itself is level 1). */
    public static final int MAX_SETTINGS_DEPTH = 16;
    /** Setting key of the sandbox ({@link RoleSandbox}). */
    public static final String SANDBOX = "sandbox";

    private static final Pattern KEY = Pattern.compile("[a-z0-9_-]{1,64}");
    /** The role's own fields: a file store keeps settings beside them, so a setting cannot take their name. */
    private static final Set<String> RESERVED = Set.of("name", "tools", "readonly", "approval", "toolpermissions",
            "settings");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The administrator role: every tool; only its approval, tool permissions and settings can be changed (default: ask, no settings). */
    public static final Role ADMIN = admin("ask", Map.of(), Map.of());

    public static Role admin(String approval, Map<String, ToolPermission> toolPermissions, Map<String, JsonNode> settings) {
        return new Role(User.ADMIN_ROLE, List.of("*"), false, approval, toolPermissions, settings);
    }

    public Role {
        tools = tools == null ? List.of() : List.copyOf(tools);
        approval = approval == null ? "ask" : approval;
        if (!"ask".equals(approval) && !"off".equals(approval)) {
            throw new IllegalArgumentException("'approval' of role " + name + " must be \"ask\" or \"off\"");
        }
        toolPermissions = toolPermissions == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(toolPermissions));
        toolPermissions.forEach((pattern, p) -> {
            if (pattern == null || pattern.isBlank() || pattern.length() > 200 || p == null) {
                throw new IllegalArgumentException("Invalid tool permission in role " + name + ": " + pattern);
            }
        });
        settings = checkSettings(name, settings);
    }

    private static Map<String, JsonNode> checkSettings(String role, Map<String, JsonNode> settings) {
        if (settings == null || settings.isEmpty()) return Map.of();
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (var e : settings.entrySet()) {
            String k = e.getKey();
            if (!settingKey(k)) {
                throw new IllegalArgumentException("Invalid settings key in role " + role + ": " + k);
            }
            JsonNode v = e.getValue();
            if (v == null || v.isNull() || v.isMissingNode()) continue;
            if (depth(v) > MAX_SETTINGS_DEPTH) {
                throw new IllegalArgumentException("Settings '" + k + "' of role " + role + " are nested deeper than "
                        + MAX_SETTINGS_DEPTH + " levels");
            }
            out.put(k, v.deepCopy());
        }
        try {
            if (MAPPER.writeValueAsBytes(out).length > MAX_SETTINGS_BYTES) {
                throw new IllegalArgumentException("Settings of role " + role + " are larger than " + MAX_SETTINGS_BYTES
                        + " bytes");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Settings of role " + role + " cannot be written as JSON");
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** Whether the key can name a setting: 1-64 of a-z, 0-9, '_' and '-', not one of the role's own fields. */
    public static boolean settingKey(String key) {
        return key != null && KEY.matcher(key).matches() && !RESERVED.contains(key);
    }

    private static int depth(JsonNode n) {
        int max = 0;
        for (JsonNode c : n) max = Math.max(max, depth(c));
        return 1 + max;
    }

    /** One setting (a copy), or null when absent. */
    public JsonNode setting(String key) {
        JsonNode v = settings.get(key);
        return v == null ? null : v.deepCopy();
    }

    /** A copy of the role with one setting replaced; a null value removes it. */
    public Role withSetting(String key, JsonNode value) {
        Map<String, JsonNode> next = new LinkedHashMap<>(settings);
        if (value == null || value.isNull()) next.remove(key);
        else next.put(key, value);
        return new Role(name, tools, readOnly, approval, toolPermissions, next);
    }

    /** The sandbox of the role's users ({@code settings.sandbox}); {@link RoleSandbox#NONE} when absent or invalid. */
    public RoleSandbox sandbox() {
        return RoleSandbox.of(settings.get(SANDBOX));
    }

    /** The decision for the tool: deny, then ask, then allow; the first list with a matching pattern wins. */
    public ToolPermission permissionOf(HarnessTool tool) {
        for (ToolPermission kind : new ToolPermission[]{ToolPermission.DENY, ToolPermission.ASK, ToolPermission.ALLOW}) {
            for (var e : toolPermissions.entrySet()) {
                if (e.getValue() == kind && Wildcard.matches(e.getKey(), tool.name())) return kind;
            }
        }
        return null;
    }

    public boolean fixed() {
        return User.ADMIN_ROLE.equals(name);
    }

    /** Whether the role lets the tool through (name patterns and read-only). */
    public boolean allows(HarnessTool tool) {
        return allowsName(tool.name()) && (!readOnly || tool.kind() == ToolKind.READ_ONLY);
    }

    public boolean allowsName(String name) {
        return Wildcard.matchesAny(tools, name);
    }

    public UserPolicy policy() {
        ToolApprovalPolicy approvals = "off".equals(approval) ? tool -> false : null;
        return new UserPolicy(this::allows, approvals, null, null, toolPermissions.isEmpty() ? null : this::permissionOf);
    }
}
