package org.kendar.llm.harness.plugin;

/** The host's plugin storage, one {@link PluginStorage} per plugin id. Published in the {@link ServiceRegistry}. */
public interface PluginStorages {
    PluginStorage forPlugin(String pluginId);
}
