package org.kendar.llm.harness;

import org.kendar.llm.harness.commands.CustomCommand;
import org.kendar.llm.harness.hooks.Hook;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginLoader;
import org.kendar.llm.harness.plugin.PluginRegistrar;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.tools.DocumentReader;
import org.kendar.llm.harness.tools.DocumentReaders;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ScopeToolCatalog;
import org.kendar.llm.harness.tools.ToolCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Collects what the host passes in code and what plugins register at install time: tools, hooks, commands, document
 * readers, replaced tools and the service registry. The builder fills it; the assemblers read it.
 */
final class PluginInstaller {

    private static final Logger LOG = LoggerFactory.getLogger(PluginInstaller.class);

    record CommandSpec(String name, String description, CustomCommand command) {
    }

    final List<HarnessTool> tools = new ArrayList<>();
    final Set<String> removedTools = new LinkedHashSet<>();
    final Set<String> replacedTools = new LinkedHashSet<>();
    final List<Consumer<Collection<String>>> nameChecks = new ArrayList<>();
    final Hooks hooks = new Hooks();
    final List<CommandSpec> commands = new ArrayList<>();
    final DocumentReaders documentReaders = new DocumentReaders();
    ServiceRegistry services = new ServiceRegistry();

    /** Resolves the plugins against the settings, logs what was decided and installs them. */
    PluginLoader.Result install(List<HarnessPlugin> explicitPlugins, boolean discover, Path pluginDir,
                                boolean pluginDirExplicit, HarnessSettings settings) {
        List<String> warnings = new ArrayList<>();
        List<PluginLoader.Candidate> found = discover
                ? PluginLoader.discover(pluginDir, pluginDirExplicit, warnings) : List.of();
        List<PluginLoader.Candidate> explicit = new ArrayList<>();
        explicitPlugins.forEach(p -> explicit.add(new PluginLoader.Candidate(p, "code")));
        PluginLoader.Result r = PluginLoader.resolve(found, explicit, settings.plugins());
        warnings.addAll(r.warnings());
        warnings.forEach(w -> LOG.warn(w));
        r.infos().forEach(i -> LOG.info(i));
        if (discover || !explicit.isEmpty()) LOG.info(r.summary());
        for (PluginLoader.Install i : r.install()) i.plugin().install(registrar(), i.settings());
        return r;
    }

    private PluginRegistrar registrar() {
        return new PluginRegistrar() {
            @Override
            public PluginRegistrar tool(HarnessTool tool) {
                tools.add(tool);
                return this;
            }

            @Override
            public PluginRegistrar hook(HookEvent event, Hook hook) {
                hooks.add(event, hook);
                return this;
            }

            @Override
            public PluginRegistrar command(String name, String description, CustomCommand command) {
                commands.add(new CommandSpec(name, description, command));
                return this;
            }

            @Override
            public PluginRegistrar documentReader(DocumentReader reader) {
                documentReaders.register(reader);
                return this;
            }

            @Override
            public PluginRegistrar replaces(String toolName) {
                removedTools.add(toolName);
                replacedTools.add(toolName);
                return this;
            }

            @Override
            public ServiceRegistry services() {
                return services;
            }

            @Override
            public ToolCatalog catalog() {
                return new ScopeToolCatalog();
            }

            @Override
            public PluginRegistrar toolNameCheck(Consumer<Collection<String>> check) {
                nameChecks.add(Objects.requireNonNull(check));
                return this;
            }
        };
    }
}
