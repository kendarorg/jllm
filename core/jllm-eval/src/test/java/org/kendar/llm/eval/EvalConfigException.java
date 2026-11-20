package org.kendar.llm.eval;

import java.util.List;

/** Invalid or missing configuration; the message lists every offending key. */
public class EvalConfigException extends RuntimeException {
    private final List<String> errors;

    public EvalConfigException(List<String> errors, Secrets secrets) {
        super(secrets.mask("Invalid jllm-eval configuration (" + errors.size() + "):\n  - " + String.join("\n  - ", errors)));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
