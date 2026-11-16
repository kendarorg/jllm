package org.kendar.llm.harness.plugin;

import java.util.List;

/**
 * A plugin of the web application. Found in the same plugin directory as harness plugins (through
 * {@link java.util.ServiceLoader}), switched on in the same settings under its id. It carries no web framework
 * classes: it registers neutral handlers and the host mounts them.
 */
public interface WebPlugin {

    /** Same rules as {@link HarnessPlugin#ID_PATTERN}. */
    String id();

    /** Ids of plugins that must be loaded first; when one is missing or disabled this plugin is skipped. */
    default List<String> requires() {
        return List.of();
    }

    void install(WebContext context);

    default void close() {
    }
}
