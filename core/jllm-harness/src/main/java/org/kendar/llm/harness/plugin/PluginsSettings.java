package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code plugins} section as the harness settings resolved it (layered settings files, then the
 * {@code jllm.plugins} properties on top). Published once so web plugins are switched on from the same place as
 * harness plugins.
 *
 * @param plugins {@code <id>: {enabled, settings}}; null when nothing is listed
 */
public record PluginsSettings(JsonNode plugins) {
}
