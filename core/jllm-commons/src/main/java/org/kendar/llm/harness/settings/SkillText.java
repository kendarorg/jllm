package org.kendar.llm.harness.settings;

import java.io.IOException;

/** Reads the instructions of a skill when the model loads it: from a file, a database row, anything. */
@FunctionalInterface
public interface SkillText {

    /** @throws IOException when the text cannot be read (e.g. the skill was removed meanwhile) */
    String read() throws IOException;
}
