package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/**
 * A bundle of tools, hooks and commands installed in one call: {@code builder.plugin(new MyPlugin())}.
 *
 * <p>Every plugin must override {@link #id()}; the id is the key of the plugin under {@code plugins} in the settings
 * file. Override whichever {@code install} method is needed.
 */
public interface HarnessPlugin {

    /** Allowed ids. */
    Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9-]{1,31}");

    /** @throws IllegalStateException when not overridden */
    default String id() {
        String c = getClass().getName();
        throw new IllegalStateException("Plugin " + c + " does not implement id(). Every plugin MUST return a unique id "
                + "matching [a-z][a-z0-9-]{1,31}, and the same id must be the key under 'plugins' in the settings "
                + "file. Override id() in " + c + ".");
    }

    default void install(PluginRegistrar registrar) {
    }

    /** @param settings {@code plugins.<id>.settings} of the settings file; an empty object when absent */
    default void install(PluginRegistrar registrar, JsonNode settings) {
        install(registrar);
    }
}
