package org.kendar.llm.eval.grade;

/** Outcome of one check: pass, fail, warn (never fails a case) or not measurable (never fails a case). */
public record CheckResult(String name, Status status, String reason) {

    public enum Status {PASS, FAIL, WARN, NOT_MEASURABLE}

    public static CheckResult pass(String name, String reason) {
        return new CheckResult(name, Status.PASS, reason);
    }

    public static CheckResult fail(String name, String reason) {
        return new CheckResult(name, Status.FAIL, reason);
    }

    public static CheckResult warn(String name, String reason) {
        return new CheckResult(name, Status.WARN, reason);
    }

    public static CheckResult notMeasurable(String name, String reason) {
        return new CheckResult(name, Status.NOT_MEASURABLE, reason);
    }

    public boolean failed() {
        return status == Status.FAIL;
    }
}
