package org.kendar.llm.harness.llm;

public class ModelException extends RuntimeException {
    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }

    /** True when the provider rejected the request because the prompt does not fit the context window. */
    public static boolean isContextOverflow(Throwable e) {
        String m = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.contains("too long") || m.contains("context length") || m.contains("context_length")
                || m.contains("maximum context") || m.contains("context window") || m.contains("too many tokens")
                || m.contains("request too large") || m.contains("413");
    }
}
