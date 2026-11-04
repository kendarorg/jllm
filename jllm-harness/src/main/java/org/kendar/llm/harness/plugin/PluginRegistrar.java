package org.kendar.llm.harness.plugin;

import org.kendar.llm.harness.commands.CustomCommand;
import org.kendar.llm.harness.hooks.Hook;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.tools.DocumentReader;
import org.kendar.llm.harness.tools.HarnessTool;

/** What a plugin can contribute; each method adds to the harness being built. */
public interface PluginRegistrar {

    PluginRegistrar tool(HarnessTool tool);

    PluginRegistrar hook(HookEvent event, Hook hook);

    /** @param name including the slash, e.g. {@code /deploy} */
    PluginRegistrar command(String name, String description, CustomCommand command);

    /** Teaches {@code read_file} a binary format such as pdf or docx. */
    PluginRegistrar documentReader(DocumentReader reader);
}
