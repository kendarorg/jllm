package org.kendar.llm.harness.instructions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code SYSTEM.md} (replaces the built-in system prompt) and {@code APPEND_SYSTEM.md} (appended), read from the global
 * {@code <jllm.home or user.home>/.jllm/} and the project {@code .jllm/}. The project file wins for SYSTEM.md; both
 * APPEND_SYSTEM.md files are appended, global first.
 *
 * @param replace replacement for the built-in prompt, or null
 * @param append  text to append, possibly empty
 */
public record SystemPromptFiles(String replace, String append) {

    public static SystemPromptFiles load(Path projectDir) {
        Path global = Path.of(System.getProperty("jllm.home", System.getProperty("user.home")), ".jllm");
        Path project = projectDir == null ? null : projectDir.resolve(".jllm");
        String replace = read(project, "SYSTEM.md");
        if (replace == null) replace = read(global, "SYSTEM.md");
        String g = read(global, "APPEND_SYSTEM.md");
        String p = read(project, "APPEND_SYSTEM.md");
        StringBuilder sb = new StringBuilder();
        if (g != null) sb.append(g);
        if (p != null) sb.append(!sb.isEmpty() ? "\n\n" : "").append(p);
        return new SystemPromptFiles(replace, sb.toString());
    }

    /** Joins an explicit append text and a file's: file first, explicit last. */
    public static String join(String first, String second) {
        boolean a = first == null || first.isBlank();
        boolean b = second == null || second.isBlank();
        return a ? (b ? "" : second) : b ? first : first + "\n\n" + second;
    }

    /** Reads a UTF-8 file; null when {@code file} is null, a blank file counts as absent. */
    public static String readFile(Path file) {
        try {
            String t = Files.readString(file, StandardCharsets.UTF_8).strip();
            return t.isEmpty() ? null : t;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static String read(Path dir, String name) {
        if (dir == null) return null;
        Path f = dir.resolve(name);
        return Files.isRegularFile(f) ? readFile(f) : null;
    }
}
