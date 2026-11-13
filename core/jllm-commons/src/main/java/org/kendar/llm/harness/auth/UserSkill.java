package org.kendar.llm.harness.auth;

import org.kendar.llm.harness.settings.SkillText;

/**
 * A skill a user policy adds on top of the harness ones. Its text is read on each load, so a change applies at once,
 * wherever the embedder keeps it.
 *
 * @param description when to use it; null = none
 */
public record UserSkill(String name, String description, SkillText text) {

    public UserSkill {
        if (name == null || text == null) throw new IllegalArgumentException("A user skill needs a name and a text");
        description = description == null ? "" : description;
    }
}
