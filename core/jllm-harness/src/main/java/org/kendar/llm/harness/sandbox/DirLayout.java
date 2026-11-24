package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Library default layout: work dir {@code base/<chatId>}, scratch {@code base/<chatId>/<agentId>}. */
public class DirLayout implements Layout {

    private final Path base;

    public DirLayout(Path base) {
        this.base = base.toAbsolutePath().normalize();
    }

    /** The layout under {@code sandbox.baseDir} of the configuration. */
    public static DirLayout of(HarnessConfig config) {
        return new DirLayout(Path.of(config.sandbox().baseDir()));
    }

    @Override
    public Path location() {
        return base;
    }

    @Override
    public Path workDir(String chatId) {
        Path dir = Layout.resolveJailed(base.toString(), chatId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create work dir " + dir, e);
        }
        return dir;
    }

    @Override
    public Path scratchDir(String chatId, String agentId) {
        Path dir = Layout.resolveJailed(workDir(chatId).toString(), agentId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create scratch dir " + dir, e);
        }
        return dir;
    }

    @Override
    public void deleteWorkDir(String chatId) {
        Path dir = workDir(chatId);
        try {
            Links.deleteTree(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete work dir " + dir, e);
        }
    }
}
