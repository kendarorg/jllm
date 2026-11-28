package org.kendar.llm.web.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.settings.AgentRepository;
import org.kendar.llm.harness.settings.InstructionRepository;
import org.kendar.llm.harness.settings.PathSettings;
import org.kendar.llm.harness.settings.SettingsLayer;
import org.kendar.llm.harness.settings.SettingsSource;
import org.kendar.llm.harness.settings.VirtualConfigProvider;

import java.nio.file.Path;
import java.util.List;

/**
 * The configuration of the web host kept in the database: the settings layer ({@link SqlSettingsSource}), the
 * instructions, the agents, the skills and the plugins' storage. Nothing is read from a working or chat directory.
 */
public final class SqlConfigProvider implements VirtualConfigProvider {

    private final SqlSettingsSource settings;
    private final SqlInstructionRepository instructions;
    private final SqlAgentRepository agents;
    private final SkillRepository skills;
    private final PluginStorages pluginStorages;
    private final List<Path> serverPaths;

    public SqlConfigProvider(SqlSettingsSource settings, SqlInstructionRepository instructions, SqlAgentRepository agents,
                             SkillRepository skills, PluginStorages pluginStorages, List<Path> serverPaths) {
        this.settings = settings;
        this.instructions = instructions;
        this.agents = agents;
        this.skills = skills;
        this.pluginStorages = pluginStorages;
        this.serverPaths = List.copyOf(serverPaths);
    }

    @Override
    public SettingsSource settings() {
        return settings;
    }

    @Override
    public InstructionRepository instructions() {
        return instructions;
    }

    @Override
    public AgentRepository agents() {
        return agents;
    }

    @Override
    public SkillRepository skills() {
        return skills;
    }

    @Override
    public PluginStorages pluginStorages() {
        return pluginStorages;
    }

    @Override
    public void putSettings(JsonNode tree) {
        PathSettings.check(tree, "sql:global");
        settings.put(SettingsLayer.Kind.GLOBAL, "sql:global", tree);
    }

    @Override
    public JsonNode settingsDocument() {
        return settings.layers().stream().filter(l -> l.kind() == SettingsLayer.Kind.GLOBAL).map(SettingsLayer::tree)
                .findFirst().orElseGet(JsonNodeFactory.instance::objectNode);
    }

    @Override
    public List<Path> serverPaths() {
        return serverPaths;
    }

    @Override
    public String describe() {
        return "sql";
    }
}
