package org.kendar.llm.web.commons;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** This module is the framework-free half of the web API: nothing in it may name Spring or the servlet API. */
class NoFrameworkTest {

    private static final List<String> FORBIDDEN = List.of("org.springframework", "jakarta.servlet", "javax.servlet");

    @Test
    void noSourceImportsAFramework() throws IOException {
        Path src = Paths.get("src", "main", "java");
        assertTrue(Files.isDirectory(src), "run from the module directory: " + src.toAbsolutePath());
        List<String> offenders;
        try (Stream<Path> files = Files.walk(src)) {
            offenders = files.filter(f -> f.toString().endsWith(".java")).flatMap(f -> {
                try {
                    return Files.readAllLines(f).stream()
                            .filter(l -> FORBIDDEN.stream().anyMatch(l::contains))
                            .map(l -> f + ": " + l.strip());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void noMainClassLoadsAFramework() throws Exception {
        Path classes = Paths.get("target", "classes");
        if (!Files.isDirectory(classes)) return; // compiled by the same build; nothing to look at otherwise
        List<String> offenders;
        try (Stream<Path> files = Files.walk(classes)) {
            offenders = files.filter(f -> f.toString().endsWith(".class")).filter(f -> {
                try {
                    String bytes = new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1);
                    return FORBIDDEN.stream().anyMatch(p -> bytes.contains(p.replace('.', '/')));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).map(Path::toString).toList();
        }
        assertEquals(List.of(), offenders);
    }
}
