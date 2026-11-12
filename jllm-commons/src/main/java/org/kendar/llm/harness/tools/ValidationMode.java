package org.kendar.llm.harness.tools;

/** How strictly the arguments of a tool call are checked against the tool's input schema. */
public enum ValidationMode {
    /** No check: the input goes to the tool as the model wrote it. */
    OFF,
    /** Harmless mistakes are repaired first ("5" for an integer, null for an optional field), the rest is rejected. */
    LENIENT,
    /** The input must match the schema exactly. */
    STRICT;

    /** Case-insensitive; null or blank gives {@link #LENIENT}. @throws IllegalArgumentException for an unknown name */
    public static ValidationMode parse(String s) {
        if (s == null || s.isBlank()) return LENIENT;
        return switch (s.strip().toLowerCase()) {
            case "off", "none", "false" -> OFF;
            case "lenient", "default", "on" -> LENIENT;
            case "strict" -> STRICT;
            default -> throw new IllegalArgumentException("Unknown validation mode '" + s + "' (off|lenient|strict)");
        };
    }
}
