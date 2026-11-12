package org.kendar.llm.js;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegexLiteralTest {

    @Test
    void regexLiteralsWorkInTheSandbox() {
        try (ContextFactory f = new ContextFactory(JsSettings.defaults());
             var ctx = f.create(new ByteArrayOutputStream())) {
            assertEquals(3, ctx.eval("js", "('banana'.match(/a/g) || []).length").asInt());
        }
    }
}
