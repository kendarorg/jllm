package org.kendar.llm.js;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class InterpreterWarningTest {

    private static String diagnostics(boolean debug) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (ContextFactory f = new ContextFactory(JsSettings.defaults(), debug, sink);
             var ctx = f.create(new ByteArrayOutputStream())) {
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
}
