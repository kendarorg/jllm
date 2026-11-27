package org.kendar.llm.eval.pin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Which build the evaluation ran on: git state of the working tree plus project, Java and OS versions. Git is optional:
 * without it, or outside a repository, every git field is {@code unknown} and the tree counts as clean.
 *
 * <p>Dirty means tracked files differ from HEAD, apart from the eval results folder (results are written there during
 * the run). Untracked files are listed for information only and never make the tree dirty. The diff hash is taken over
 * what git reports, so line-ending conversion configured in git (autocrlf) is already normalised in it.
 *
 * @param dirtyFiles     changed tracked files with their status code, at most {@link #MAX_FILES}
 * @param dirtyFileCount number of changed tracked files, before the cap
 * @param untrackedFiles untracked, not ignored files, at most {@link #MAX_FILES}; informational
 * @param diffHash       SHA-256 of the uncommitted diff against HEAD; {@code none} when clean, {@code unknown} without git
 */
public record BuildInfo(String commit, String branch, boolean dirty, List<String> dirtyFiles, int dirtyFileCount,
                        List<String> untrackedFiles, int untrackedFileCount, String diffHash, String describe,
                        String projectVersion, String javaVersion, String os) {

    public static final int MAX_FILES = 50;
    public static final String UNKNOWN = "unknown";
    private static final long GIT_TIMEOUT_SECONDS = 30;

    /** Nothing known about git: used when there is no run (unit tests) and when git is unavailable. */
    public static BuildInfo unknown(String projectVersion) {
        return new BuildInfo(UNKNOWN, UNKNOWN, false, List.of(), 0, List.of(), 0, UNKNOWN, UNKNOWN, projectVersion,
                System.getProperty("java.version"), osName());
    }

    /**
     * @param workDir        folder inside the repository (git is run in its top level)
     * @param resultsDir     the eval results folder, whose changes do not count; may be outside the repository
     * @param projectVersion version of the project under test, or {@code unknown}
     */
    public static BuildInfo detect(Path workDir, Path resultsDir, String projectVersion) {
        BuildInfo none = unknown(projectVersion);
        String topText = text(workDir, "rev-parse", "--show-toplevel");
        if (topText == null) return none;
        Path top = Path.of(topText);
        String commit = text(top, "rev-parse", "HEAD");
        if (commit == null) return none;
        String branch = text(top, "rev-parse", "--abbrev-ref", "HEAD");
        String describe = text(top, "describe", "--tags", "--always");
        String skip = relativeTo(top, resultsDir);

        List<String> dirty = new ArrayList<>();
        int dirtyCount = 0;
        byte[] status = run(top, "--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=no");
        if (status != null) {
            String[] parts = new String(status, StandardCharsets.UTF_8).split("\0");
            for (int i = 0; i < parts.length; i++) {
                String e = parts[i];
                if (e.length() < 4) continue;
                String code = e.substring(0, 2).strip();
                String path = e.substring(3);
                // a rename or copy is followed by the original path
                if (e.charAt(0) == 'R' || e.charAt(0) == 'C' || e.charAt(1) == 'R' || e.charAt(1) == 'C') i++;
                if (inside(path, skip)) continue;
                dirtyCount++;
                if (dirty.size() < MAX_FILES) dirty.add(code + " " + path);
            }
        }

        List<String> untracked = new ArrayList<>();
        int untrackedCount = 0;
        byte[] others = run(top, "ls-files", "--others", "--exclude-standard", "-z");
        if (others != null) {
            for (String p : new String(others, StandardCharsets.UTF_8).split("\0")) {
                if (p.isEmpty() || inside(p, skip)) continue;
                untrackedCount++;
                if (untracked.size() < MAX_FILES) untracked.add(p);
            }
        }

        String diffHash = "none";
        if (dirtyCount > 0) {
            List<String> args = new ArrayList<>(List.of("diff", "HEAD", "--binary", "--no-color", "--no-ext-diff",
                    "--no-textconv", "--", "."));
            if (skip != null && !skip.isEmpty()) args.add(":(exclude)" + skip);
            byte[] diff = run(top, args.toArray(String[]::new));
            diffHash = diff == null ? UNKNOWN : sha256(diff);
        }
        return new BuildInfo(commit, branch == null ? UNKNOWN : branch, dirtyCount > 0, List.copyOf(dirty), dirtyCount,
                List.copyOf(untracked), untrackedCount, diffHash, describe == null ? UNKNOWN : describe,
                projectVersion, System.getProperty("java.version"), osName());
    }

    /** First 8 characters of the commit, or {@code unknown}. */
    public String shortCommit() {
        return commit.length() > 8 ? commit.substring(0, 8) : commit;
    }

    /** Short commit with a {@code +dirty} marker when the tree has tracked changes. */
    public String label() {
        return shortCommit() + (dirty ? "+dirty" : "");
    }

    private static String osName() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch");
    }

    /** Path of {@code dir} relative to {@code top} with '/' separators; null when it lies outside or cannot be told. */
    private static String relativeTo(Path top, Path dir) {
        if (dir == null) return null;
        try {
            Path t = real(top);
            Path d = real(dir);
            if (!d.startsWith(t)) return null;
            return t.relativize(d).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The real path of the folder, or of its nearest existing parent plus the rest. */
    private static Path real(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        try {
            if (existing == null) return abs;
            return existing.toRealPath().resolve(existing.relativize(abs));
        } catch (IOException e) {
            return abs;
        }
    }

    private static boolean inside(String path, String folder) {
        if (folder == null || folder.isEmpty()) return false;
        return path.equals(folder) || path.startsWith(folder + "/");
    }

    private static String text(Path dir, String... args) {
        byte[] out = run(dir, args);
        if (out == null) return null;
        String s = new String(out, StandardCharsets.UTF_8).strip();
        return s.isEmpty() ? null : s;
    }

    /**
     * Runs git with an argument list (no shell) in {@code dir}; the whole output is read while waiting, with a
     * timeout. Null when git is missing, fails, times out or the thread is interrupted (the flag is restored).
     */
    private static byte[] run(Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            p = pb.start();
        } catch (IOException e) {
            return null;
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // nothing to send
        }
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> {
            try (InputStream in = p.getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                return null;
            }
        });
        try {
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            return out.get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            p.destroyForcibly();
            return null;
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
