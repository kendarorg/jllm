package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Checks (and may repair) the arguments the model passed to a tool, before permissions, approval and execution.
 * Add one with {@code AgentHarness.Builder#addToolInputValidator}, or replace the default with
 * {@code Builder#toolInputValidator}.
 */
@FunctionalInterface
public interface ToolInputValidator {

    /**
     * @param input the arguments as the model wrote them; must not be modified
     * @return the arguments to use, plus the problems that make the call invalid (empty = valid)
     */
    Outcome validate(HarnessTool tool, JsonNode input, ValidationMode mode);

    record Outcome(JsonNode input, List<String> errors) {
        public Outcome {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public static Outcome ok(JsonNode input) {
            return new Outcome(input, List.of());
        }

        public static Outcome invalid(JsonNode input, List<String> errors) {
            return new Outcome(input, errors);
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** Runs the validators in order; each sees the input repaired by the previous one; errors are collected. */
    static ToolInputValidator chain(List<ToolInputValidator> validators) {
        List<ToolInputValidator> all = List.copyOf(validators);
        return (tool, input, mode) -> {
            if (mode == ValidationMode.OFF) return Outcome.ok(input);
            JsonNode current = input;
            java.util.ArrayList<String> errors = new java.util.ArrayList<>();
            for (ToolInputValidator v : all) {
                Outcome o = v.validate(tool, current, mode);
                if (o.input() != null) current = o.input();
                errors.addAll(o.errors());
            }
            return new Outcome(current, errors);
        };
    }
}
