package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.permission.PermissionRules;

import java.util.List;

/** Regex rules, see {@link PermissionRules}. Evaluated deny, ask, allow; settings only. */
public record PermissionSettings(List<String> deny, List<String> ask, List<String> allow) {
    public PermissionSettings {
        deny = deny == null ? List.of() : List.copyOf(deny);
        ask = ask == null ? List.of() : List.copyOf(ask);
        allow = allow == null ? List.of() : List.copyOf(allow);
    }
}
