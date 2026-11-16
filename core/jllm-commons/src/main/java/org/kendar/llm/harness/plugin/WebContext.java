package org.kendar.llm.harness.plugin;

import com.fasterxml.jackson.databind.JsonNode;

/** What the host gives a web plugin: settings, storage, events, services and a way to register endpoints. */
public interface WebContext {
    JsonNode settings();

    PluginStorage storage();

    PluginEvents events();

    ServiceRegistry services();

    /**
     * Registers an endpoint under the plugin's reserved mount ({@code /api/plugins/<id>/...}).
     *
     * @param pattern a path with optional {@code {name}} segments, e.g. {@code /runs/{id}/answer}
     */
    WebContext route(String method, String pattern, WebHandler handler);
}
