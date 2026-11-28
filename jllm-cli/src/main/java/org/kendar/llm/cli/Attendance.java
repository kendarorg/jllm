package org.kendar.llm.cli;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

/**
 * Whether anyone can answer a question: not with {@code --prompt}, not when input is piped (data waiting on stdin is
 * the script, not an answer), not in a CI environment ({@code CI} set and not false/0).
 */
final class Attendance {

    /** The environment; tests replace it. */
    static Function<String, String> env = System::getenv;

    private Attendance() {
    }

    static boolean unattended(Map<String, String> args) throws IOException {
        if (args.containsKey("prompt") || System.in.available() > 0) return true;
        String ci = env.apply("CI");
        return ci != null && !ci.isBlank() && !ci.equalsIgnoreCase("false") && !ci.equals("0");
    }
}
