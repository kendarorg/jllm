package org.kendar.llm.harness.plugin;

/** A bundle of tools, hooks and commands installed in one call: {@code builder.plugin(new MyPlugin())}. */
public interface HarnessPlugin {

    void install(PluginRegistrar registrar);
}
