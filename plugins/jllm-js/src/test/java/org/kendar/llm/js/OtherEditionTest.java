package org.kendar.llm.js;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The isolate of the edition that is NOT set up here. The regular build runs the edition {@code scripts/setup}
 * installed; this test starts a JVM with the same class path minus every GraalJS jar plus the jars of the other
 * edition ({@code -Djllm.graal.other.dir=<folder of jars>}, e.g. made with
 * {@code release/graaljs/resolve.sh community <dir>}), and checks that the isolate is verified and a script runs.
 * Without the property it is skipped. {@code -Djllm.graal.other.edition=community|oracle} is the expected edition.
 */
class OtherEditionTest {

    private static final List<String> GRAAL = List.of("polyglot", "truffle", "regex", "js-language", "js-isolate",
            "collections", "nativebridge", "jniutils", "icu4j", "xz", "nativeimage", "word");

    private static boolean graalJar(String file) {
        String n = file.toLowerCase(Locale.ROOT);
        return n.endsWith(".jar") && GRAAL.stream().anyMatch(g -> n.startsWith(g + "-"));
    }

    @Test
    void theOtherEditionRunsIsolatedScripts() throws Exception {
        String dir = System.getProperty("jllm.graal.other.dir");
        Assumptions.assumeTrue(dir != null && !dir.isBlank(), "set -Djllm.graal.other.dir to run it");
        String expected = System.getProperty("jllm.graal.other.edition", "").toLowerCase(Locale.ROOT);
        String own = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> cp = new ArrayList<>();
        for (String e : own.split(File.pathSeparator)) {
            if (!graalJar(Path.of(e).getFileName().toString())) cp.add(e);
        }
        try (Stream<Path> jars = Files.list(Path.of(dir))) {
            jars.filter(p -> p.toString().endsWith(".jar")).forEach(p -> cp.add(p.toString()));
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT)
                .contains("win") ? "java.exe" : "java");
        Process p = new ProcessBuilder(java.toString(), "-cp", String.join(File.pathSeparator, cp),
                "-Djllm.graal.dir=" + Path.of(dir).toAbsolutePath(), IsolatedSmoke.class.getName())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), out);

        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("isolate=true"), out);
        assertTrue(out.contains("ran=true profile=isolated"), out);
        if (!expected.isEmpty()) assertTrue(out.contains("edition=" + expected.toUpperCase(Locale.ROOT)), out);
    }
}
