package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.sandbox.FileBackend;

import org.kendar.llm.harness.tools.PathResolver.JailedPath;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Checkpoints.class);

    private static final String DIR = ".jllm/checkpoints";
    /** Turn directory names: at most 18 digits, so they parse as a long and the next one does not overflow. */
    /** The most a {@code created.txt} is read: a longer one is not ours. */
    private static final long MAX_CREATED_BYTES = 16L * 1024 * 1024;
    private static final String TURN_NAME = "\\d{1,18}";

    private final Map<String, Long> current = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> touched = new ConcurrentHashMap<>();

    /** Whether a jailed host path is inside the checkpoint data of {@code workDir} (directly or through a link). */
    public static boolean isCheckpointPath(Path workDir, JailedPath p) {
        Path dir = workDir.toAbsolutePath().normalize().resolve(DIR);
        if (p.lexical().startsWith(dir)) return true;
        return PathResolver.realPath(p).startsWith(PathResolver.realKey(dir));
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
        if (Files.isDirectory(workDir)) {
            try {
                JailedPath root = PathResolver.resolve(workDir, DIR + "/" + chatId, ExternalLinks.NONE, false);
                BasicFileAttributes a = PathJail.stat(root);
                if (a != null && a.isDirectory()) {
                    for (PathJail.Entry e : PathJail.list(root)) next = Math.max(next, turnNumber(e.name()) + 1);
                }
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
        } catch (IOException | RuntimeException e) { // boundary: no checkpoints yet, or the container is not reachable
            // no checkpoints yet (or the container is not reachable: the first tool call will say so)
            log.debug("No checkpoint state loaded: {}", e.toString());
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
                } catch (IOException | RuntimeException e) { // boundary: roll back on any failure, rethrown unchanged
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
        } catch (RuntimeException e) { // boundary: forget the path on any failure, rethrown unchanged
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
                return new UndoResult(0, restored, deleted, List.of());
            }
            if (nums.isEmpty()) return new UndoResult(0, restored, deleted, List.of());
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
            return new UndoResult(picked.size(), restored, deleted, List.of());
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

    /** Records the state of {@code file} before a write; no-op after the first touch in the turn. */
    public void record(ToolContext ctx, JailedPath file) {
        record(ctx, file, StorageQuota.UNLIMITED);
    }

    /** As {@link #record(ToolContext, JailedPath)}, counting the backup in {@code quota}. */
    public void record(ToolContext ctx, JailedPath file, StorageQuota quota) {
        Long turn = current.get(ctx.chatId());
        if (turn == null) return;
        Path work = ctx.workDir().toAbsolutePath().normalize();
        Path abs = file.lexical();
        if (!abs.startsWith(work) || abs.startsWith(work.resolve(".jllm"))) return; // outside the project or our own data
        // the scratch dir of the agent: with the local layout it lies inside the work dir, and it is never undone
        Path scratch = ctx.scratchDir() == null ? null : ctx.scratchDir().toAbsolutePath().normalize();
        if (scratch != null && !scratch.equals(work) && abs.startsWith(scratch)) return;
        String rel = work.relativize(abs).toString().replace('\\', '/');
        Set<String> seen = touched.computeIfAbsent(ctx.chatId(), k -> ConcurrentHashMap.newKeySet());
        if (!seen.add(rel)) return;
        String dir = DIR + "/" + ctx.chatId() + "/" + turn;
        try {
            BasicFileAttributes st = PathJail.stat(file);
            if (st != null) {
                JailedPath backup = PathResolver.resolve(work, dir + "/files/" + rel, ExternalLinks.NONE, true);
                PathJail.createParentDirectories(backup);
                BasicFileAttributes old = PathJail.statNoFollow(backup);
                StorageQuota.Reservation r = quota.reserve(ctx.chatId(), backup.lexical(), old != null ? old.size() : 0,
                        st.size());
                try {
                    PathJail.deleteIfExists(backup); // a link is removed, not followed
                    try (InputStream in = PathJail.newInputStream(file);
                         OutputStream out = Channels.newOutputStream(
                                 PathJail.openWrite(backup, Set.of(StandardOpenOption.CREATE_NEW)))) {
                        in.transferTo(out);
                    }
                } catch (IOException | RuntimeException e) { // boundary: roll back on any failure, rethrown unchanged
                    r.rollback();
                    throw e;
                }
            } else {
                JailedPath created = PathResolver.resolve(work, dir + "/created.txt", ExternalLinks.NONE, true);
                PathJail.createParentDirectories(created);
                synchronized (this) {
                    try (OutputStream out = Channels.newOutputStream(PathJail.openWrite(created,
                            Set.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
                        out.write((rel + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            seen.remove(rel);
            throw new UncheckedIOException("Cannot checkpoint " + rel, e);
        } catch (RuntimeException e) { // boundary: forget the path on any failure, rethrown unchanged
            seen.remove(rel);
            throw e;
        }
    }

    /** Outcome of {@link #undo}. */
    public record UndoResult(int turns, List<String> restored, List<String> deleted, List<String> refused) {
        public String message() {
            if (turns == 0) return "Nothing to undo";
            StringBuilder sb = new StringBuilder("Undid " + turns + " turn(s): restored " + restored.size()
                    + " file(s), removed " + deleted.size() + " created file(s).");
            restored.forEach(f -> sb.append("\n  restored ").append(f));
            deleted.forEach(f -> sb.append("\n  deleted  ").append(f));
            refused.forEach(f -> sb.append("\n  not restored ").append(f));
            sb.append("\nFiles changed by shell commands are not restored.");
            return sb.toString();
        }
    }

    /**
     * Where a relative path from checkpoint data goes in the work dir, or null when it must not be touched: absolute,
     * with {@code ..}, inside {@code .jllm}, or leaving the work dir through a link that {@code links} does not allow
     * writing through (then {@code refused} gets a line naming the setting).
     */
    private static JailedPath safeTarget(Path work, String rel, ExternalLinks links, List<String> refused) {
        String p = rel.strip().replace('\\', '/');
        if (p.isEmpty() || p.startsWith("/") || p.matches("^[A-Za-z]:.*")) return null;
        for (String seg : p.split("/")) {
            if (seg.equals("..")) return null;
        }
        JailedPath target;
        try {
            target = PathResolver.resolve(work, p, links, true);
        } catch (IllegalArgumentException e) {
            if (String.valueOf(e.getMessage()).contains("is a link that leaves the work dir")) {
                refused.add(p + " (a link that leaves the work dir; files.externalLinks is " + links.settingValue()
                        + ", writing through it needs readwrite)");
            }
            return null; // invalid path characters, or a link leaving the work dir
        }
        if (target.components().isEmpty() || target.components().get(0).equals(".jllm")) return null;
        List<String> real = PathResolver.realComponents(target);
        if (real == null && links.followsOnWrite()) return target; // outside the work dir through a link: allowed
        if (real == null || real.isEmpty() || real.get(0).equals(".jllm")) return null;
        return target;
    }

    private static boolean isRealDirectory(JailedPath p) throws IOException {
        BasicFileAttributes a = PathJail.statNoFollow(p);
        return a != null && a.isDirectory() && !PathResolver.isLink(a);
    }

    /** Restores the files of the last {@code turns} turns that edited files, newest first. */
    public UndoResult undo(String chatId, Path workDir, int turns, ExternalLinks links) {
        Path work = workDir.toAbsolutePath().normalize();
        List<String> restored = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        if (!Files.isDirectory(work)) return new UndoResult(0, restored, deleted, List.of());
        JailedPath root = PathResolver.resolve(work, DIR + "/" + chatId, ExternalLinks.NONE, false);
        List<PathJail.Entry> dirs;
        try {
            if (!isRealDirectory(root)) return new UndoResult(0, restored, deleted, List.of());
            dirs = PathJail.list(root).stream()
                    .filter(e -> turnNumber(e.name()) >= 0 && e.attributes().isDirectory() && !e.isLink())
                    .sorted(Comparator.comparingLong((PathJail.Entry e) -> turnNumber(e.name())).reversed())
                    .limit(Math.max(1, turns)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> seenRestored = new HashSet<>();
        for (PathJail.Entry entry : dirs) {
            int refusedBefore = refused.size();
            JailedPath dir = root.child(entry.name());
            try {
                JailedPath created = dir.child("created.txt");
                BasicFileAttributes ca = PathJail.statNoFollow(created);
                if (ca != null && ca.isRegularFile()) {
                    String text = new String(PathJail.readAllBytes(created, MAX_CREATED_BYTES), StandardCharsets.UTF_8);
                    for (String line : (Iterable<String>) text.lines()::iterator) {
                        JailedPath target = safeTarget(work, line, links, refused);
                        if (target == null || isRealDirectory(target)) continue;
                        PathJail.deleteIfExists(target); // a link is removed, not followed
                        deleted.add(String.join("/", target.components()));
                    }
                }
                JailedPath files = dir.child("files");
                if (isRealDirectory(files)) {
                    int skip = files.components().size();
                    PathJail.walk(files, (b, a) -> {
                        if (!a.isRegularFile() || b.components().size() <= skip) return PathJail.Action.CONTINUE;
                        BasicFileAttributes own = PathJail.statNoFollow(b);
                        if (own == null || !own.isRegularFile()) return PathJail.Action.CONTINUE; // a link: not trusted
                        String rel = String.join("/", b.components().subList(skip, b.components().size()));
                        JailedPath target = safeTarget(work, rel, links, refused);
                        if (target == null) return PathJail.Action.CONTINUE;
                        try {
                            PathJail.createParentDirectories(target);
                            if (PathJail.isDirectory(target)) return PathJail.Action.CONTINUE;
                            try (InputStream in = PathJail.newInputStream(b)) {
                                AtomicFiles.write(target, in); // through a link inside the jail: its file was edited
                            }
                        } catch (IllegalArgumentException e) {
                            return PathJail.Action.CONTINUE; // a link leaving the work dir appeared on the way
                        }
                        if (seenRestored.add(rel)) restored.add(rel);
                        return PathJail.Action.CONTINUE;
                    });
                }
                if (refused.size() == refusedBefore) PathJail.deleteTree(dir); // kept when a restore was refused: retry with another policy
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot undo " + entry.name(), e);
            }
        }
        touched.remove(chatId);
        current.remove(chatId);
        return new UndoResult(dirs.size(), restored, deleted, refused);
    }
}
