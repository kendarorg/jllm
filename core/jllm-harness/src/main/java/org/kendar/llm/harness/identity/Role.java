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
 * @param approval {@code off}: no approval prompts; {@code ask} (default): the harness policy; {@code always}: the
 *                 harness policy, and no mode (auto, edit) waives a question
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
    /** Setting key: {@code true} lets the instruction files of a chat directory join the chats of the role (off by default). */
    public static final String SCOPED_INSTRUCTIONS = "scoped-instructions";
    /** Setting key of the sandbox ({@link RoleSandbox}). */
    public static final String SANDBOX = "sandbox";
    /** Setting key: the permission modes the role's users may select, an array of mode names (default: all). */
    public static final String ALLOWED_MODES = "allowed-modes";
    /** Setting key: {@code true} when nobody answers the permission questions of the role's chats (admin-only). */
    public static final String UNATTENDED = "unattended";

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
        if (!"ask".equals(approval) && !"off".equals(approval) && !"always".equals(approval)) {
            throw new IllegalArgumentException("'approval' of role " + name + " must be \"ask\", \"always\" or \"off\"");
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

    /**
     * The permission modes the role's users may select; null when every mode is allowed. A stored value that is not a
     * valid list gives only {@code default}: a bad value must never allow more.
     */
    public java.util.Set<org.kendar.llm.harness.permission.PermissionMode> allowedModes() {
        JsonNode n = settings.get(ALLOWED_MODES);
        if (n == null || n.isNull()) return null;
        try {
            return modes(n);
        } catch (IllegalArgumentException e) {
            return java.util.Set.of(org.kendar.llm.harness.permission.PermissionMode.DEFAULT);
        }
    }

    /**
     * Strict read of an {@link #ALLOWED_MODES} value.
     *
     * @throws IllegalArgumentException when it is not a non-empty array of mode names (default, plan, edit, auto)
     */
    public static java.util.Set<org.kendar.llm.harness.permission.PermissionMode> modes(JsonNode n) {
        if (n == null || !n.isArray() || n.isEmpty()) {
            throw new IllegalArgumentException("'" + ALLOWED_MODES + "' must be a non-empty array of default, plan, edit, auto");
        }
        java.util.Set<org.kendar.llm.harness.permission.PermissionMode> out = new java.util.LinkedHashSet<>();
        for (JsonNode m : n) {
            String v = m.isTextual() ? m.asText().trim().toLowerCase(java.util.Locale.ROOT) : "";
            switch (v) {
                case "default" -> out.add(org.kendar.llm.harness.permission.PermissionMode.DEFAULT);
                case "plan" -> out.add(org.kendar.llm.harness.permission.PermissionMode.PLAN);
                case "edit" -> out.add(org.kendar.llm.harness.permission.PermissionMode.EDIT);
                case "auto" -> out.add(org.kendar.llm.harness.permission.PermissionMode.AUTO);
                default -> throw new IllegalArgumentException("'" + ALLOWED_MODES + "': unknown mode " + m
                        + " (default, plan, edit, auto)");
            }
        }
        return out;
    }

    /** The stored form of an allowed modes list: lower case names, once each, in the order default, plan, edit, auto. */
    public static JsonNode modesJson(java.util.Set<org.kendar.llm.harness.permission.PermissionMode> modes) {
        var a = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (var m : org.kendar.llm.harness.permission.PermissionMode.values()) {
            if (modes.contains(m)) a.add(m.name().toLowerCase(java.util.Locale.ROOT));
        }
        return a;
    }

    /** Whether nobody answers the permission questions of the role's chats ({@link #UNATTENDED}). */
    public boolean unattended() {
        return settings.containsKey(UNATTENDED) && settings.get(UNATTENDED).asBoolean(false);
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

    /** Role level "always ask" ({@code approval: always}). */
    public boolean alwaysAsk() {
        return "always".equals(approval);
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

    /** Whether chats of the role get the instruction files of their chat directory ({@link #SCOPED_INSTRUCTIONS}). */
    public boolean scopedInstructions() {
        return settings.containsKey(SCOPED_INSTRUCTIONS) && settings.get(SCOPED_INSTRUCTIONS).asBoolean(false);
    }

    public UserPolicy policy() {
        ToolApprovalPolicy approvals = "off".equals(approval) ? tool -> false : null;
        return new UserPolicy(this::allows, approvals, null, null, toolPermissions.isEmpty() ? null : this::permissionOf,
                null, scopedInstructions() ? UserPolicy.Scoped.USER_CONTENT : UserPolicy.Scoped.OFF, allowedModes(),
                unattended(), alwaysAsk());
    }
}
