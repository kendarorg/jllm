package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * One self-contained sandbox per chat under a base directory X: {@code X/<chatId>} is the work dir and holds its own
 * {@code .kendhar} (history, scratchpad, checkpoints). Deleting the chat deletes {@code X/<chatId>} entirely.
 */
public class ChatDirSandboxExecutor extends LocalDirSandboxExecutor {

    private final Path base;

    public ChatDirSandboxExecutor(HarnessConfig config, Path base) {
        super(config);
        this.base = base.toAbsolutePath().normalize();
    }

    /** The base directory X holding one directory per chat. */
    @Override
    public Path location() {
        return base;
    }

    @Override
    public Path workDir(String chatId) {
        Path dir = resolveJailed(base.toString(), chatId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create work dir " + dir, e);
        }
        return dir;
    }

    /** {@code X/<chatId>/.kendhar/history}: the per-agent history directories live below it. */
    public Path historyRoot(String chatId) {
        return resolveJailed(base.toString(), chatId).resolve(".kendhar").resolve("history");
    }

    @Override
    public Path scratchDir(String chatId, String agentId) {
        Path dir = resolveJailed(resolveJailed(base.toString(), chatId).resolve(".kendhar").resolve("scratchpad")
                .toString(), agentId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create scratch dir " + dir, e);
        }
        return dir;
    }

    @Override
    public void deleteWorkDir(String chatId) {
        Path dir = resolveJailed(base.toString(), chatId);
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete chat dir " + dir, e);
        }
    }
}
