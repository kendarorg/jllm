package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.sandbox.FileBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * File checkpoints: the first time a user turn touches a file with {@code write_file} or {@code edit_file}, the old
 * content is copied to {@code <workDir>/.jllm/checkpoints/<chatId>/<turn>/files/<path>} (a file that did not exist is
 * listed in {@code created.txt}). {@link #undo} restores the files of the last turns.
 * <p>
 * Files changed by shell commands are not recorded and not restored. Turns that touched no file leave nothing, so
 * "n turns" counts turns with edits.
 * <p>
 * The checkpoint directory is inside the work dir, so a shell command can write there: {@link #undo} trusts nothing in
 * it (paths must stay in the work dir and out of {@code .jllm}, links are skipped). The file tools refuse to write
 * there ({@link #isCheckpointPath}).
 */
public final class Checkpoints {

    private static final String DIR = ".jllm/checkpoints";
    /** Turn directory names: at most 18 digits, so they parse as a long and the next one does not overflow. */
    private static final String TURN_NAME = "\\d{1,18}";

    private final Map<String, Long> current = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> touched = new ConcurrentHashMap<>();

    /** Whether a host path is inside the checkpoint data of {@code workDir} (directly or through a link). */
    public static boolean isCheckpointPath(Path workDir, Path p) {
        Path work = workDir.toAbsolutePath().normalize();
        Path dir = work.resolve(DIR);
        if (p.toAbsolutePath().normalize().startsWith(dir)) return true;
        Path real = PathLocks.realKey(p);
        return real.startsWith(PathLocks.realKey(dir));
    }

    /** Whether a container target is inside the checkpoint data of the work dir. */
    public static boolean isCheckpointPath(FileBackend fb, FileBackend.Target t) {
        if (t.root() != FileBackend.Root.WORKSPACE) return false;
        List<String> segs = segments(fb, t.path());
        return segs != null && segs.size() >= 2 && segs.get(0).equals(".jllm") && segs.get(1).equals("checkpoints");
    }

    private static long turnNumber(String name) {
        return name.matches(TURN_NAME) ? Long.parseLong(name) : -1;
    }

    /** Starts a new turn for the chat; edits recorded from now on belong to it. */
    public void beginTurn(String chatId, Path workDir) {
        long next = 1;
        Path root = root(chatId, workDir);
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.list(root)) {
                next = s.mapToLong(p -> turnNumber(p.getFileName().toString())).max().orElse(0) + 1;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        current.put(chatId, Math.max(1, next));
        touched.put(chatId, ConcurrentHashMap.newKeySet());
    }

    // ── container work dir (docker daemon mode): the same layout, kept inside the container ───────────

    private static String remoteRoot(String chatId) {
        return DIR + "/" + chatId;
    }

    /** As {@link #beginTurn}, for files in a container. */
    public void beginTurn(String chatId, FileBackend fb) {
        long next = 1;
        try {
            for (JsonNode e : fb.list(new FileBackend.Target(FileBackend.Root.WORKSPACE, remoteRoot(chatId)), true)
                    .path("entries")) {
                long n = turnNumber(e.path("name").asText());
                if (n >= 0) next = Math.max(next, n + 1);
            }
        } catch (IOException | RuntimeException e) {
            // no checkpoints yet (or the container is not reachable: the first tool call will say so)
        }
        current.put(chatId, next);
        touched.put(chatId, ConcurrentHashMap.newKeySet());
    }

    /** Segments of a work dir path relative to {@code /workspace}; null when outside it or with {@code ..}. */
    private static List<String> segments(FileBackend fb, String path) {
        String p = path.replace('\\', '/');
        String work = fb.workRoot();
        if (p.startsWith("/")) {
            if (!p.equals(work) && !p.startsWith(work + "/")) return null;
            p = p.substring(work.length());
        }
        List<String> out = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) return null;
            out.add(seg);
        }
        return out;
    }

    /** Path of a work dir file relative to {@code /workspace}, or null when outside it or inside our own data. */
    private static String relative(FileBackend fb, String path) {
        List<String> out = segments(fb, path);
        if (out == null || out.isEmpty() || out.get(0).equals(".jllm")) return null;
        return String.join("/", out);
    }

    /** Records the state of a container file before a write; no-op after the first touch in the turn. */
    public void record(ToolContext ctx, FileBackend.Target target) {
        record(ctx, target, StorageQuota.UNLIMITED);
    }

    /** As {@link #record(ToolContext, FileBackend.Target)}, counting the backup in {@code quota}. */
    public void record(ToolContext ctx, FileBackend.Target target, StorageQuota quota) {
        FileBackend fb = ctx.files();
        Long turn = current.get(ctx.chatId());
        if (turn == null || fb == null || target.root() != FileBackend.Root.WORKSPACE) return;
        String rel = relative(fb, target.path());
        if (rel == null) return;
        Set<String> seen = touched.computeIfAbsent(ctx.chatId(), k -> ConcurrentHashMap.newKeySet());
        if (!seen.add(rel)) return;
        String dir = remoteRoot(ctx.chatId()) + "/" + turn;
        try {
            FileBackend.Stat st = fb.stat(target);
            if (st.exists()) {
                StorageQuota.Reservation r = quota.reserveRemote(ctx.chatId(), st.size());
                try {
                    fb.write(new FileBackend.Target(FileBackend.Root.WORKSPACE, dir + "/files/" + rel),
                            fb.read(target, 0, -1), false);
                } catch (IOException | RuntimeException e) {
                    r.rollback();
                    throw e;
                }
            } else {
                fb.write(new FileBackend.Target(FileBackend.Root.WORKSPACE, dir + "/created.txt"),
                        (rel + "\n").getBytes(StandardCharsets.UTF_8), true);
            }
        } catch (IOException e) {
            seen.remove(rel);
            throw new UncheckedIOException("Cannot checkpoint " + rel, e);
        } catch (RuntimeException e) {
            seen.remove(rel);
            throw e;
        }
    }

    /** A relative path from checkpoint data that is safe to touch in a container work dir; null otherwise. */
    private static String safeRemote(String rel) {
        String p = rel.strip().replace('\\', '/');
        if (p.isEmpty() || p.startsWith("/") || p.matches("^[A-Za-z]:.*")) return null;
        List<String> out = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) return null;
            out.add(seg);
        }
        if (out.isEmpty() || out.get(0).equals(".jllm")) return null;
        return String.join("/", out);
    }

    /** As {@link #undo}, for files in a container. */
    public UndoResult undo(String chatId, FileBackend fb, int turns) {
        List<String> restored = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        FileBackend.Root w = FileBackend.Root.WORKSPACE;
        try {
            List<Long> nums = new ArrayList<>();
            try {
                for (JsonNode e : fb.list(new FileBackend.Target(w, remoteRoot(chatId)), true).path("entries")) {
                    long n = turnNumber(e.path("name").asText());
                    if (n >= 0 && e.path("dir").asBoolean(false) && !e.path("link").asBoolean(false)) nums.add(n);
                }
            } catch (java.nio.file.NoSuchFileException e) {
                return new UndoResult(0, restored, deleted);
            }
            if (nums.isEmpty()) return new UndoResult(0, restored, deleted);
            nums.sort(Comparator.reverseOrder());
            List<Long> picked = nums.subList(0, Math.clamp(turns, 1, nums.size()));
            Set<String> seen = new HashSet<>();
            for (long n : picked) {
                String dir = remoteRoot(chatId) + "/" + n;
                FileBackend.Target created = new FileBackend.Target(w, dir + "/created.txt");
                if (fb.stat(created).isFile()) {
                    for (String line : new String(fb.read(created, 0, -1), StandardCharsets.UTF_8).split("\n")) {
                        String rel = safeRemote(line);
                        if (rel == null) continue;
                        FileBackend.Target t = new FileBackend.Target(w, rel);
                        if (fb.stat(t).exists()) fb.delete(t);
                        deleted.add(rel);
                    }
                }
                restoreDir(fb, dir + "/files", "", seen, restored);
                fb.delete(new FileBackend.Target(w, dir));
            }
            touched.remove(chatId);
            current.remove(chatId);
            return new UndoResult(picked.size(), restored, deleted);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot undo", e);
        }
    }

    private static void restoreDir(FileBackend fb, String filesDir, String rel, Set<String> seen, List<String> restored)
            throws IOException {
        FileBackend.Root w = FileBackend.Root.WORKSPACE;
        String at = rel.isEmpty() ? filesDir : filesDir + "/" + rel;
        if (!fb.stat(new FileBackend.Target(w, at)).isDir()) return;
        for (JsonNode e : fb.list(new FileBackend.Target(w, at), true).path("entries")) {
            String name = e.path("name").asText();
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")
                    || e.path("link").asBoolean(false)) {
                continue;
            }
            String child = rel.isEmpty() ? name : rel + "/" + name;
            if (safeRemote(child) == null) continue;
            if (e.path("dir").asBoolean(false)) {
                restoreDir(fb, filesDir, child, seen, restored);
            } else {
                fb.write(new FileBackend.Target(w, child),
                        fb.read(new FileBackend.Target(w, filesDir + "/" + child), 0, -1), false);
                if (seen.add(child)) restored.add(child);
            }
        }
    }

    private static Path root(String chatId, Path workDir) {
        return workDir.toAbsolutePath().normalize().resolve(DIR).resolve(chatId);
    }

    /** Records the state of {@code file} before a write; no-op after the first touch in the turn. */
    public void record(ToolContext ctx, Path file) {
        record(ctx, file, StorageQuota.UNLIMITED);
    }

    /** As {@link #record(ToolContext, Path)}, counting the backup in {@code quota}. */
    public void record(ToolContext ctx, Path file, StorageQuota quota) {
        Long turn = current.get(ctx.chatId());
        if (turn == null) return;
        Path work = ctx.workDir().toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        if (!abs.startsWith(work) || abs.startsWith(work.resolve(".jllm"))) return; // scratch dir or our own data
        String rel = work.relativize(abs).toString().replace('\\', '/');
        Set<String> seen = touched.computeIfAbsent(ctx.chatId(), k -> ConcurrentHashMap.newKeySet());
        if (!seen.add(rel)) return;
        Path dir = root(ctx.chatId(), work).resolve(String.valueOf(turn));
        try {
            if (Files.exists(abs)) {
                Path backup = dir.resolve("files").resolve(rel);
                Files.createDirectories(backup.getParent());
                // a link planted in the checkpoint dir must not send the copy elsewhere
                if (!backup.getParent().toRealPath().startsWith(work.resolve(DIR).toRealPath())) {
                    throw new IOException("Checkpoint directory leaves " + DIR);
                }
                StorageQuota.Reservation r = quota.reserve(ctx.chatId(), backup,
                        Files.exists(backup, LinkOption.NOFOLLOW_LINKS) ? Files.size(backup) : 0, Files.size(abs));
                try {
                    Files.deleteIfExists(backup); // a link is removed, not followed
                    Files.copy(abs, backup);
                } catch (IOException | RuntimeException e) {
                    r.rollback();
                    throw e;
                }
            } else {
                Files.createDirectories(dir);
                synchronized (this) {
                    Files.writeString(dir.resolve("created.txt"), rel + "\n", StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                }
            }
        } catch (IOException e) {
            seen.remove(rel);
            throw new UncheckedIOException("Cannot checkpoint " + rel, e);
        } catch (RuntimeException e) {
            seen.remove(rel);
            throw e;
        }
    }

    /** Outcome of {@link #undo}. */
    public record UndoResult(int turns, List<String> restored, List<String> deleted) {
        public String message() {
            if (turns == 0) return "Nothing to undo";
            StringBuilder sb = new StringBuilder("Undid " + turns + " turn(s): restored " + restored.size()
                    + " file(s), removed " + deleted.size() + " created file(s).");
            restored.forEach(f -> sb.append("\n  restored ").append(f));
            deleted.forEach(f -> sb.append("\n  deleted  ").append(f));
            sb.append("\nFiles changed by shell commands are not restored.");
            return sb.toString();
        }
    }

    /**
     * Where a relative path from checkpoint data goes in the work dir, or null when it must not be touched: absolute,
     * with {@code ..}, inside {@code .jllm}, or leaving the work dir through a link.
     */
    private static Path safeTarget(Path work, Path realWork, String rel) {
        String p = rel.strip().replace('\\', '/');
        if (p.isEmpty() || p.startsWith("/") || p.matches("^[A-Za-z]:.*")) return null;
        for (String seg : p.split("/")) {
            if (seg.equals("..")) return null;
        }
        Path target;
        try {
            target = work.resolve(p).normalize();
        } catch (RuntimeException e) {
            return null; // invalid path characters
        }
        if (!target.startsWith(work) || target.equals(work) || target.startsWith(work.resolve(".jllm"))) return null;
        Path real = PathLocks.realKey(target);
        if (!real.startsWith(realWork) || real.equals(realWork) || real.startsWith(realWork.resolve(".jllm"))) {
            return null;
        }
        return target;
    }

    /** Deletes a directory tree without entering links or junctions (they are removed, not followed). */
    private static void deleteTree(Path dir) throws IOException {
        Path realDir = dir.toRealPath();
        List<Path> all;
        try (Stream<Path> s = Files.walk(dir)) {
            all = s.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : all) {
            if (!p.equals(dir) && !PathLocks.realKey(p.getParent()).startsWith(realDir)) continue;
            Files.deleteIfExists(p);
        }
    }

    /** Restores the files of the last {@code turns} turns that edited files, newest first. */
    public UndoResult undo(String chatId, Path workDir, int turns) {
        Path work = workDir.toAbsolutePath().normalize();
        Path root = root(chatId, work);
        List<String> restored = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return new UndoResult(0, restored, deleted);
        Path realWork;
        try {
            realWork = work.toRealPath();
            if (!root.toRealPath().startsWith(realWork.resolve(DIR))) {
                throw new IOException("Checkpoint directory leaves the work dir");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(root)) {
            dirs = s.filter(p -> turnNumber(p.getFileName().toString()) >= 0
                            && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingLong((Path p) -> turnNumber(p.getFileName().toString())).reversed())
                    .limit(Math.max(1, turns)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> seenRestored = new HashSet<>();
        for (Path dir : dirs) {
            try {
                Path created = dir.resolve("created.txt");
                if (Files.isRegularFile(created, LinkOption.NOFOLLOW_LINKS)) {
                    for (String line : Files.readAllLines(created, StandardCharsets.UTF_8)) {
                        Path target = safeTarget(work, realWork, line);
                        if (target == null || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) continue;
                        Files.deleteIfExists(target); // a link is removed, not followed
                        deleted.add(work.relativize(target).toString().replace('\\', '/'));
                    }
                }
                Path files = dir.resolve("files");
                if (Files.isDirectory(files, LinkOption.NOFOLLOW_LINKS)) {
                    Path realFiles = files.toRealPath();
                    try (Stream<Path> s = Files.walk(files)) { // does not follow links; junctions: real path check
                        for (Path b : (Iterable<Path>) s.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)
                                && PathLocks.realKey(f).startsWith(realFiles))::iterator) {
                            String rel = files.relativize(b).toString().replace('\\', '/');
                            Path target = safeTarget(work, realWork, rel);
                            if (target == null) continue;
                            Files.createDirectories(target.getParent());
                            if (safeTarget(work, realWork, rel) == null) continue; // a link appeared on the way
                            if (Files.isDirectory(target)) continue;
                            AtomicFiles.copy(b, target); // through a link (checked to stay inside): its file was edited
                            if (seenRestored.add(rel)) restored.add(rel);
                        }
                    }
                }
                deleteTree(dir);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot undo " + dir.getFileName(), e);
            }
        }
        touched.remove(chatId);
        current.remove(chatId);
        return new UndoResult(dirs.size(), restored, deleted);
    }
}
