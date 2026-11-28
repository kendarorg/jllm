package org.kendar.llm.harness.settings;

import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.plugin.PluginStorages;

/**
 * Where the configuration of a harness comes from: the settings layers, the instructions, the sub-agents, the skills
 * and the plugins' storage. The CLI reads it from files (folder trust gated); the web host from a
 * {@link VirtualConfigProvider}, never from a project or chat directory.
 */
public interface ConfigProvider {

    SettingsSource settings();

    /** System prompt, global, role and user instructions. */
    InstructionRepository instructions();

    /** Sub-agents with inline prompts only. */
    AgentRepository agents();

    /** The skills (harness skills folded in). */
    SkillRepository skills();

    PluginStorages pluginStorages();
}
