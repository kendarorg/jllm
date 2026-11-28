package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the user said about a project folder. Nothing a folder holds ({@code .jllm/}, instruction files, MCP servers,
 * hooks) takes effect before the folder is trusted: the answer is remembered per canonical folder path, bound to the
 * hash of the folder's {@code .jllm/} content ({@link #hashOf}); a changed hash asks again. Besides the folder, a
 * trusted folder's single definitions that start a process or send a token (stdio MCP servers, stop hooks, OAuth
 * blocks) are answered one by one ({@link #lookup}, {@link #remember}, bound to {@link #fingerprint}). The settings
 * that loosen the user's own ({@link ProjectTrustFilter#LOOSENING_KEYS}) are approved per folder too and kept in the
 * {@link Record}. {@link FileFolderTrust} keeps the answers in a file of the user.
 */
public interface FolderTrust {

    /** What the program writes into {@code .jllm/} while it runs: not configuration, never part of the hash. */
    List<String> RUNTIME_DIRS = List.of("scratchpad", "history", "checkpoints");

    /** Asks the user whether a project's stdio MCP server (or hook, OAuth block) may run. */
    @FunctionalInterface
    interface Prompt {

        /** No one to ask (web server, scripts): only an answer remembered earlier counts. */
        Prompt NONE = (projectDir, server, commandLine) -> null;

        /**
         * @param commandLine the command and its arguments, for display
         * @return true to run the server (remembered), false to refuse it (remembered), null when no one can answer
         * (nothing remembered; the server is skipped)
         */
        Boolean ask(Path projectDir, String server, String commandLine);
    }

    /**
     * What is remembered of a trusted folder.
     *
     * @param path       the canonical path
     * @param sha256     hash of the folder's {@code .jllm/} content when the user trusted it
     * @param trustedAt  epoch milliseconds
     * @param loosenings the loosening settings the user approved (see {@link ProjectTrustFilter#LOOSENING_KEYS})
     */
    record Record(String path, String sha256, long trustedAt, Set<String> loosenings) {
        public Record {
            loosenings = loosenings == null ? Set.of() : Set.copyOf(loosenings);
        }
    }

    /** The remembered answer for this exact definition, or null when there is none. */
    Boolean lookup(Path projectDir, String server, JsonNode definition);

    /** Remembers the answer for this definition; a failure to store it is logged, not thrown (it is asked again). */
    void remember(Path projectDir, String server, JsonNode definition, boolean trusted);

    /** The record of the folder, empty when it was never trusted. */
    Optional<Record> record(Path projectDir);

    /**
     * Trusts the folder as it is now: stores the hash of its {@code .jllm/} content, the time and the approved
     * loosenings. A failure to store it is logged, not thrown (it is asked again).
     */
    void trust(Path projectDir, Set<String> loosenings);

    /**
     * Adds {@code key} to the approved loosenings of the folder's record; nothing when the folder has no record or its
     * {@code .jllm/} content changed since (the setting is asked again).
     */
    void approveLoosening(Path projectDir, String key);

    /** True when the folder was trusted and its {@code .jllm/} content is still the one trusted. */
    default boolean isTrusted(Path projectDir) {
        Optional<Record> r = record(projectDir);
        return r.isPresent() && r.get().sha256().equals(hashOf(projectDir));
    }

    /** A trust that remembers nothing and trusts no folder (servers that never read a project folder). */
    static FolderTrust none() {
        return new FolderTrust() {
            @Override
            public Boolean lookup(Path projectDir, String server, JsonNode definition) {
                return null;
            }

            @Override
            public void remember(Path projectDir, String server, JsonNode definition, boolean trusted) {
            }

            @Override
            public Optional<Record> record(Path projectDir) {
                return Optional.empty();
            }

            @Override
            public void trust(Path projectDir, Set<String> loosenings) {
            }

            @Override
            public void approveLoosening(Path projectDir, String key) {
            }
        };
    }

    /** The key of a project: its real absolute path, or the normalized one when it does not exist. */
    static String projectKey(Path projectDir) {
        Path p = projectDir.toAbsolutePath().normalize();
        try {
            p = p.toRealPath();
        } catch (IOException | SecurityException ignored) {
            // not existing: the normalized path
        }
        return p.toString();
    }

    /** SHA-256 (hex) of the definition's JSON: what an answer is bound to. */
    static String fingerprint(JsonNode definition) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(new ObjectMapper().writeValueAsString(definition).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * SHA-256 (hex) of the content of {@code <projectDir>/.jllm}: the relative paths, sorted, each followed by its
     * bytes (a directory by a marker, a symbolic link by its target text, never followed). The runtime directories
     * ({@link #RUNTIME_DIRS}, directly under {@code .jllm}) are left out. A missing {@code .jllm} has a hash too.
     */
    static String hashOf(Path projectDir) {
        Path root = projectDir.toAbsolutePath().normalize().resolve(".jllm");
        List<Path> all = new ArrayList<>();
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.walkFileTree(root, Set.<FileVisitOption>of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                        if (d.equals(root)) return FileVisitResult.CONTINUE;
                        if (d.getParent().equals(root) && RUNTIME_DIRS.contains(d.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        all.add(d);
                        return a.isSymbolicLink() || a.isOther() ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        all.add(f);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path f, IOException e) {
                        all.add(f);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        List<String> rels = new ArrayList<>();
        for (Path p : all) rels.add(root.relativize(p).toString().replace('\\', '/'));
        Collections.sort(rels);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String rel : rels) {
                Path p = root.resolve(rel);
                md.update(rel.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                if (Files.isSymbolicLink(p)) {
                    md.update((byte) 'L');
                    md.update(String.valueOf(Files.readSymbolicLink(p)).getBytes(StandardCharsets.UTF_8));
                } else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    md.update((byte) 'D');
                } else {
                    md.update((byte) 'F');
                    try {
                        md.update(Files.readAllBytes(p));
                    } catch (IOException e) {
                        md.update((byte) '?'); // unreadable: still differs from a readable one
                    }
                }
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
