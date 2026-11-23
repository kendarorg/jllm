package org.kendar.llm.js;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The interpreter-only warning goes through the log bridge: absent normally, in the debug stream when asked for. */
class InterpreterWarningTest {

    private static String diagnostics(boolean debug) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (var f = new ConstrainedProfile(JsSettings.defaults(), false, debug, sink);
             var ctx = f.newContext(new ByteArrayOutputStream(), 10_000)) {
            ctx.eval("js", "1+1");
        }
        return sink.toString();
    }

    @Test
    void theInterpreterOnlyWarningIsAbsentAtNormalLogLevel() {
        assertEquals("", diagnostics(false));
    }

    @Test
    void theInterpreterOnlyWarningIsLoggedAtDebug() {
        String d = diagnostics(true);

        // on a JVM with the Graal JIT there is nothing to warn about
        org.junit.jupiter.api.Assumptions.assumeTrue(!d.isEmpty(), "this JVM runs GraalJS compiled");
        assertTrue(d.toLowerCase().contains("interpreter") || d.toLowerCase().contains("runtime compilation"), d);
    }

    @Test
    void theLogBridgeAcceptsLinesWithBothLineEndings() throws Exception {
        try (var out = JsLog.engineStream()) {
            out.write("line one\r\nline two\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
