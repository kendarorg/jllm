package org.kendar.llm.harness;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The module must stay independent of Spring, JPA, Micrometer and any host server. */
class FrameworkFreeTest {

    private static final List<String> BANNED = List.of("org.springframework", "jakarta.persistence",
            "javax.persistence", "io.micrometer", "org.kendar.llm.server");

    @Test
    void mainSourcesImportNothingBanned() throws IOException {
        Path root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "run from the module directory");
        try (Stream<Path> files = Files.walk(root)) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                try {
                    return Files.readAllLines(p).stream().filter(l -> BANNED.stream().anyMatch(l::contains))
                            .map(l -> p + ": " + l.strip());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertTrue(offenders.isEmpty(), "banned references: " + offenders);
        }
    }

    @Test
    void noSpringOnTheClasspath() {
        try {
            Class.forName("org.springframework.context.ApplicationContext");
            throw new AssertionError("Spring is on the test classpath");
        } catch (ClassNotFoundException expected) {
            // good
        }
    }
}
