package org.kendar.llm.harness.settings;

/**
 * A skill: a markdown file of instructions the model loads on demand.
 *
 * @param description when to use it; default: the {@code description:} line in the front matter of the file
 * @param path        the SKILL.md file
 */
public record SkillSettings(String name, String description, String path) {
}
