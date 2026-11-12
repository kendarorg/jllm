package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

/**
 * Where the file tools read and write when the sandbox does not share the host file system: the exec daemon of a
 * {@link RemoteExecSandboxExecutor} container. Paths are strings in the container's (POSIX) file system, never host
 * {@link java.nio.file.Path}s. Formatting, limits and edit logic stay in the tools; a backend only moves raw data.
 * <p>
 * Failures: {@link IllegalArgumentException} for a path the daemon refuses (outside the root), {@link
 * java.nio.file.NoSuchFileException} for a missing file, {@link IOException} for anything else.
 */
public interface FileBackend {

    /** Which tree a path is relative to. */
    enum Root {
        /** The project / chat files, {@code /workspace}. */
        WORKSPACE,
        /** The agent's scratch directory ({@code null} agent: the whole scratchpad). */
        SCRATCH
    }

    /** A root and a path inside it. */
    record Target(Root root, String path) {
    }

    /** @param type {@code file}, {@code dir}, {@code link}, {@code linkdir} or {@code other}; null when missing */
    record Stat(boolean exists, String type, long size, long mtime, String mode) {
        public boolean isFile() {
            return exists && "file".equals(type);
        }

        public boolean isDir() {
            return exists && ("dir".equals(type) || "linkdir".equals(type));
        }
    }

    /** Work directory in the container, shown to the model ({@code /workspace}). */
    String workRoot();

    /** Scratch directory of the agent in the container, shown to the model. */
    String scratchRoot();

    /** Where a read goes: the work dir, or an absolute path inside it. */
    Target readTarget(String path);

    /**
     * Where a write goes. In plan mode ({@code planMode}) the work dir is read-only: relative paths go to the scratch
     * directory and absolute paths must already be inside it.
     */
    Target writeTarget(String path, boolean planMode);

    Stat stat(Target t) throws IOException;

    /** {@code limit < 0}: to the end of the file. */
    byte[] read(Target t, long offset, long limit) throws IOException;

    void write(Target t, byte[] data, boolean append) throws IOException;

    /** Regular files of the work dir matching a glob, sorted, relative to the work dir. */
    List<String> glob(String pattern) throws IOException;

    /** Raw {@code path:line:text} hits of a regex (Python syntax), optionally restricted to a path and a glob. */
    List<String> grep(String pattern, String path, String glob) throws IOException;

    /** One directory: {@code {path, entries:[{name, dir, link, size, mtime, mode, target?}], more}}. */
    JsonNode list(Target t, boolean all) throws IOException;

    /** Capped recursive listing as indented text. */
    String tree(Target t, int depth) throws IOException;

    void mkdir(Target t) throws IOException;

    void move(Root root, String from, String to) throws IOException;

    /** Deletes a file or a directory tree (symlinks are removed, not followed). */
    void delete(Target t) throws IOException;
}
