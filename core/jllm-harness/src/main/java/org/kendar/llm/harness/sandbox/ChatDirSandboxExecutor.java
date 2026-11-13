package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One self-contained sandbox per chat under a base directory X: {@code X/<chatId>} is the work dir and holds its own
 * {@code .jllm} (history, scratchpad, checkpoints). Deleting the chat deletes {@code X/<chatId>} entirely.
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

    /** {@code X/<chatId>/.jllm/history}: the per-agent history directories live below it. */
    public Path historyRoot(String chatId) {
        return resolveJailed(base.toString(), chatId).resolve(".jllm").resolve("history");
    }

    @Override
    public Path scratchDir(String chatId, String agentId) {
        Path dir = resolveJailed(resolveJailed(base.toString(), chatId).resolve(".jllm").resolve("scratchpad")
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
        try {
            Links.deleteTree(dir); // junctions and symlinks are removed, never followed
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete chat dir " + dir, e);
        }
    }
}
