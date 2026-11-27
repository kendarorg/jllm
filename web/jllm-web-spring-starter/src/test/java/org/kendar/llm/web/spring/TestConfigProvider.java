package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.settings.AgentRepository;
import org.kendar.llm.harness.settings.InstructionRepository;
import org.kendar.llm.harness.settings.SettingsSource;
import org.kendar.llm.harness.settings.VirtualConfigProvider;

import java.nio.file.Path;
import java.util.List;

/** An empty virtual configuration for the tests that need no content. */
final class TestConfigProvider implements VirtualConfigProvider {

    @Override
    public void putSettings(JsonNode tree) {
    }

    @Override
    public JsonNode settingsDocument() {
        return JsonNodeFactory.instance.objectNode();
    }

    @Override
    public List<Path> serverPaths() {
        return List.of();
    }

    @Override
    public String describe() {
        return "test";
    }

    @Override
    public SettingsSource settings() {
        return List::of;
    }

    @Override
    public InstructionRepository instructions() {
        return InstructionRepository.NONE;
    }

    @Override
    public AgentRepository agents() {
        return AgentRepository.NONE;
    }

    @Override
    public SkillRepository skills() {
        return null;
    }

    @Override
    public PluginStorages pluginStorages() {
        return pluginId -> null;
    }
}
