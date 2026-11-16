package org.kendar.llm.workflows;

/** Any failure inside a flow; {@code kind} names the class of error for the handler and the history. */
public class FlowException extends RuntimeException {
    public enum Kind { ACTION, SHAPE, CHOOSER, TIMEOUT, PERMISSION, CHILD, LIMIT, INVALID_FLOW, REQUEST }

    private final Kind kind;

    public FlowException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public FlowException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
