package org.kendar.llm.harness.agent;

/** Who can answer a permission question of a run. */
public enum RunAttendance {
    /** A person answers: a call that would ask is put to them. */
    ATTENDED,
    /**
     * Nobody answers now, but the caller can (a flow step that turns the question into one for its user): a call that
     * would ask is not run and is reported as {@code approval_unavailable}.
     */
    COLLECT,
    /** Nobody can answer: every call that would ask is denied, the denial goes back to the model. */
    UNATTENDED;

    /** Whether a question can be put to a person. */
    public boolean asks() {
        return this == ATTENDED;
    }
}
