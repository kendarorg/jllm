package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line sandbox: the work dir is the real project directory (every chat), scratch directories live in
 * {@code <cwd>/.jllm/scratchpad}; when the project directory is not writable they fall back to the configured sandbox base dir
 * (the system temp dir by default), still under the chat id. No isolation: commands run as this process's user.
 */
public class CwdSandboxExecutor extends LocalDirSandboxExecutor {

    private final Path cwd;
    private final Path scratchRoot;

    static final String SCRATCH_DIR = ".jllm/scratchpad";

    public CwdSandboxExecutor(HarnessConfig config, Path cwd) {
        super(config);
        this.cwd = cwd.toAbsolutePath().normalize();
        this.scratchRoot = projectScratch(this.cwd, super.location());
    }

    /** {@code <cwd>/.jllm/scratchpad} when it can be created and written, else the fallback root. */
    private static Path projectScratch(Path cwd, Path fallback) {
        Path dir = cwd.resolve(SCRATCH_DIR);
        try {
            Files.createDirectories(dir);
            if (Files.isWritable(dir)) {
                Path probe = Files.createTempFile(dir, ".probe", null);
                Files.deleteIfExists(probe);
                return dir;
            }
        } catch (IOException | SecurityException e) {
            // read-only project directory
        }
        return fallback;
    }

    /** Root of the per chat scratch directories. */
    public Path scratchRoot() {
        return scratchRoot;
    }

    /**
     * Root of the chat histories ({@code <chatId>/<agentId>}): {@code <cwd>/.jllm/history}, or {@code history}
     * under the fallback root when the project directory is not writable.
     */
    public Path historyRoot() {
        return scratchRoot.startsWith(cwd) ? cwd.resolve(".jllm").resolve("history") : scratchRoot.resolve("history");
    }

    /** The project directory. */
    @Override
    public Path location() {
        return cwd;
    }

    @Override
    public Path workDir(String chatId) {
        return cwd;
    }

    @Override
    public Path scratchDir(String chatId, String agentId) {
        Path dir = resolveJailed(chatScratch(chatId).toString(), agentId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create scratch dir " + dir, e);
        }
        return dir;
    }

    @Override
    public boolean sharedWorkDir() {
        return true;
    }

    @Override
    public Path chatStorage(String chatId) {
        return chatScratch(chatId);
    }

    private Path chatScratch(String chatId) {
        return resolveJailed(scratchRoot.toString(), chatId);
    }

    /** Deletes the scratch directories of the chat; the project directory is never touched. */
    @Override
    public void deleteWorkDir(String chatId) {
        Path dir = chatScratch(chatId);
        try {
            Links.deleteTree(dir); // junctions and symlinks are removed, never followed
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete scratch dir " + dir, e);
        }
    }
}
