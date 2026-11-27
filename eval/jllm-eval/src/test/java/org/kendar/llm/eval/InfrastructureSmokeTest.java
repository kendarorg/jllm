package org.kendar.llm.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.llm.ModelClient;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** Needs a live target: a one-line prompt through the target client answers, and run.json is written. */
@Tag("smoke")
class InfrastructureSmokeTest {

    @Test
    void targetAnswersAndMetadataIsWritten() throws Exception {
        EvalRun run = EvalRun.get();
        ModelClient target = EvalModels.target();
        String answer = target.simpleComplete("", "Name one color.", 256);
        assertNotNull(answer);
        assertFalse(answer.isBlank());
        assertTrue(Files.isRegularFile(run.metadataFile()));
        assertEquals(run.config().selfGraded(), run.metadata().selfGraded());
    }
}
