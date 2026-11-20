package org.kendar.llm.harness.context.compaction;

import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.Prompts;

import static org.junit.jupiter.api.Assertions.*;

class CompactionPromptRulesTest {

    @Test
    void summaryKeepsToolFindingsAndMayOmitEmptySections() {
        String create = CompactionPrompt.create(null);
        assertTrue(create.contains("what tool calls found") || create.contains("Keep what tool calls found"), create);
        assertTrue(create.contains("omit a section that has nothing to say"));
        assertTrue(CompactionPrompt.update(null).contains("Keep what tool calls found"));
    }

    @Test
    void updateDropsFinishedStepsButNeverValuesOfTheEarlierCheckpoint() {
        // "drop what is done" alone made a model drop codes it had only acknowledged
        String update = CompactionPrompt.update(null);
        assertTrue(update.contains("never values"), update);
        assertTrue(update.contains("stays word for word unless the user replaced it"), update);
    }

    @Test
    void systemPromptTellsTheModelNotToRepeatACheckpointAsItsAnswer() {
        String system = Prompts.load("system-prompt.md");
        assertTrue(system.contains("[Summary of the earlier ...]"));
        assertTrue(system.contains("never repeat it as your answer"));
    }
}
