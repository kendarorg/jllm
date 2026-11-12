package org.kendar.llm.js;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class JsSandboxTest {

    static JsSandbox box;

    @BeforeAll
    static void open() {
        box = new JsSandbox(new JsSettings(2_000_000L, 120, 10, true));
    }

    @AfterAll
    static void shut() {
        box.close();
    }

    static Stream<String> forbidden() {
        return Stream.of(
                "return fetch('http://localhost/');",
                "return new XMLHttpRequest();",
                "return new WebSocket('ws://localhost/');",
                "return Java.type('java.lang.Runtime');",
                "return Packages.java.lang.Runtime;",
                "return java.lang.System.getenv();",
                "return require('fs');",
                "return load('x.js');",
                "print('x');",
                "printErr('x');",
                "return Graal.version;",
                "return Polyglot.eval('js', '1');",
                "return process.env;",
                "return __invoke('x', '{}');",
                "while (true) {}",
                "function f() { return f() + 1; } return f();",
                "return read('pom.xml');",
                "return readFile('pom.xml');");
    }

    @ParameterizedTest
    @MethodSource("forbidden")
    void forbiddenThingsFailTheScript(String code) {
        JsResult r = box.run(code);

        assertFalse(r.ok(), code + " -> " + r);
        assertNotNull(r.error());
    }

    @Test
    void returnValueIsJson() {
        JsResult r = box.run("return {a: [1, 2], b: 'x'};");

        assertTrue(r.ok(), r.toString());
        assertEquals("{\"a\":[1,2],\"b\":\"x\"}", r.value());
    }

    @Test
    void noReturnGivesNullValue() {
        JsResult r = box.run("var x = 1;");

        assertTrue(r.ok());
        assertNull(r.value());
    }

    @Test
    void consoleOutputIsCaptured() {
        JsResult r = box.run("console.log('hello', 3); console.error('bad'); return 1;");

        assertTrue(r.ok());
        assertTrue(r.console().contains("hello 3") && r.console().contains("bad"), r.console());
        assertFalse(r.truncated());
    }

    @Test
    void consoleAbove50KbIsCut() {
        JsResult r = box.run("for (let i = 0; i < 2000; i++) console.log('x'.repeat(100)); return 1;");

        assertTrue(r.ok(), r.toString());
        assertTrue(r.truncated());
        assertEquals(JsSandbox.CONSOLE_MAX_BYTES, r.console().length());
    }

    @Test
    void errorsCarryTheLine() {
        JsResult r = box.run("var a = 1;\nvar b = 2;\nthrow new Error('boom');");

        assertFalse(r.ok());
        assertTrue(r.error().contains("boom") && r.error().contains("at line 3"), r.error());
    }

    @Test
    void statementLimitEndsTheRunWithoutThrowing() {
        JsResult r = box.run("while (true) {}");

        assertFalse(r.ok());
        assertTrue(r.error().contains("statements"), r.error());
    }

    @Test
    void timeoutEndsTheRunWithoutThrowing() {
        try (JsSandbox slow = new JsSandbox(new JsSettings(Long.MAX_VALUE, 120, 10, true))) {
            long t = System.nanoTime();
            JsResult r = slow.run("while (true) {}", 1);

            assertFalse(r.ok());
            assertTrue(r.error().startsWith("Timeout"), r.error());
            assertTrue((System.nanoTime() - t) / 1_000_000 < 10_000);
            assertTrue(slow.run("return 1;").ok(), "the next call works");
        }
    }

    @Test
    void nothingSurvivesBetweenRuns() {
        assertTrue(box.run("globalThis.leak = 42; return 1;").ok());

        JsResult r = box.run("return typeof globalThis.leak;");

        assertEquals("\"undefined\"", r.value());
    }

    @Test
    void concurrentRunsDoNotShareState() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<JsResult>>();
            for (int i = 0; i < 8; i++) {
                int n = i;
                futures.add(pool.submit(() -> box.run("globalThis.n = " + n + "; return globalThis.n;")));
            }
            for (int i = 0; i < 8; i++) assertEquals(String.valueOf(i), futures.get(i).get().value());
        } finally {
            pool.shutdownNow();
        }
    }
}
