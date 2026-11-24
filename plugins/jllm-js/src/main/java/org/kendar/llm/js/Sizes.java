package org.kendar.llm.js;

/** Memory sizes as the engine's options take them. */
final class Sizes {
    static final long MIB = 1024L * 1024L;

    private Sizes() {
    }

    /** Whole megabytes, rounded up, at least one, such as {@code 256MB}. */
    static String megabytes(long bytes) {
        return Math.max(1, (bytes + MIB - 1) / MIB) + "MB";
    }
}
