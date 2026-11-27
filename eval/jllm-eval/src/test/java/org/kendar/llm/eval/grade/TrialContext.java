package org.kendar.llm.eval.grade;

import org.kendar.llm.eval.run.TrialTrace;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What a check sees.
 *
 * @param caseName    name of the case
 * @param trace       events and recorded requests of the trial
 * @param workspace   the chat work directory after the run
 * @param filesBefore relative path to SHA-256 of every file in the workspace before the first turn
 * @param userTurns   the user messages sent
 * @param extras      free-form values planted by fixtures (canaries, fake server logs...), by key
 */
public record TrialContext(String caseName, TrialTrace trace, Path workspace, Map<String, String> filesBefore,
                           List<String> userTurns, Map<String, Object> extras) {

    public String finalAnswer() {
        return trace.finalAnswer();
    }
}
