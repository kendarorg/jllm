package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.sandbox.FileBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * File checkpoints: the first time a user turn touches a file with {@code write_file} or {@code edit_file}, the old
 * content is copied to {@code <workDir>/.kendhar/checkpoints/<chatId>/<turn>/files/<path>} (a file that did not exist is
 * listed in {@code created.txt}). {@link #undo} restores the files of the last turns.
 * <p>
 * Files changed by shell commands are not recorded and not restored. Turns that touched no file leave nothing, so
 * "n turns" counts turns with edits.
 */
public final class Checkpoints {

    private static final String DIR = ".kendhar/checkpoints";

    private final Map<String, Integer> current = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> touched = new ConcurrentHashMap<>();

    /** Starts a new turn for the chat; edits recorded from now on belong to it. */
    public void beginTurn(String chatId, Path workDir) {
        int next = 1;
        Path root = root(chatId, workDir);
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.list(root)) {
                next = s.map(p -> p.getFileName().toString()).filter(n -> n.matches("\\d+"))
                        .mapToInt(Integer::parseInt).max().orElse(0) + 1;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        current.put(chatId, next);
        touched.put(chatId, ConcurrentHashMap.newKeySet());
    }

    // ── container work dir (docker daemon mode): the same layout, kept inside the container ───────────

    private static String remoteRoot(String chatId) {
        return DIR + "/" + chatId;
    }

    /** As {@link #beginTurn}, for files in a container. */
    public void beginTurn(String chatId, FileBackend fb) {
        int next = 1;
        try {
            for (JsonNode e : fb.list(new FileBackend.Target(FileBackend.Root.WORKSPACE, remoteRoot(chatId)), true)
                    .path("entries")) {
                String n = e.path("name").asText();
                if (n.matches("\\d+")) next = Math.max(next, Integer.parseInt(n) + 1);
            }
        } catch (IOException | RuntimeException e) {
            // no checkpoints yet (or the container is not reachable: the first tool call will say so)
        }
        current.put(chatId, next);
        touched.put(chatId, ConcurrentHashMap.newKeySet());
    }

    /** Path of a work dir file relative to {@code /workspace}, or null when outside it or inside our own data. */
    private static String relative(FileBackend fb, String path) {
        String p = path.replace('\\', '/');
        String work = fb.workRoot();
        if (p.startsWith("/")) {
            if (!p.equals(work) && !p.startsWith(work + "/")) return null;
            p = p.substring(work.length());
        }
        java.util.List<String> out = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) return null;
            out.add(seg);
        }
        if (out.isEmpty() || out.get(0).equals(".kendhar")) return null;
        return String.join("/", out);
    }

    /** Records the state of a container file before a write; no-op after the first touch in the turn. */
    public void record(ToolContext ctx, FileBackend.Target target) {
        FileBackend fb = ctx.files();
        Integer turn = current.get(ctx.chatId());
        if (turn == null || fb == null || target.root() != FileBackend.Root.WORKSPACE) return;
        String rel = relative(fb, target.path());
        if (rel == null) return;
        if (!touched.computeIfAbsent(ctx.chatId(), k -> ConcurrentHashMap.newKeySet()).add(rel)) return;
        String dir = remoteRoot(ctx.chatId()) + "/" + turn;
        try {
            if (fb.stat(target).exists()) {
                fb.write(new FileBackend.Target(FileBackend.Root.WORKSPACE, dir + "/files/" + rel),
                        fb.read(target, 0, -1), false);
            } else {
                fb.write(new FileBackend.Target(FileBackend.Root.WORKSPACE, dir + "/created.txt"),
                        (rel + "\n").getBytes(StandardCharsets.UTF_8), true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot checkpoint " + rel, e);
        }
    }

    /** As {@link #undo}, for files in a container. */
    public UndoResult undo(String chatId, FileBackend fb, int turns) {
        List<String> restored = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        FileBackend.Root w = FileBackend.Root.WORKSPACE;
        try {
            List<Integer> nums = new ArrayList<>();
            try {
                for (JsonNode e : fb.list(new FileBackend.Target(w, remoteRoot(chatId)), true).path("entries")) {
                    String n = e.path("name").asText();
                    if (n.matches("\\d+")) nums.add(Integer.parseInt(n));
                }
            } catch (java.nio.file.NoSuchFileException e) {
                return new UndoResult(0, restored, deleted);
            }
            nums.sort(Comparator.reverseOrder());
            List<Integer> picked = nums.subList(0, Math.min(nums.size(), Math.max(1, turns)));
            Set<String> seen = new HashSet<>();
            for (int n : picked) {
                String dir = remoteRoot(chatId) + "/" + n;
                FileBackend.Target created = new FileBackend.Target(w, dir + "/created.txt");
                if (fb.stat(created).exists()) {
                    for (String rel : new String(fb.read(created, 0, -1), StandardCharsets.UTF_8).split("\n")) {
                        if (rel.isBlank()) continue;
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
            String child = rel.isEmpty() ? e.path("name").asText() : rel + "/" + e.path("name").asText();
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
        Integer turn = current.get(ctx.chatId());
        if (turn == null) return;
        Path work = ctx.workDir().toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        if (!abs.startsWith(work) || abs.startsWith(work.resolve(".kendhar"))) return; // scratch dir or our own data
        String rel = work.relativize(abs).toString().replace('\\', '/');
        if (!touched.computeIfAbsent(ctx.chatId(), k -> ConcurrentHashMap.newKeySet()).add(rel)) return;
        Path dir = root(ctx.chatId(), work).resolve(String.valueOf(turn));
        try {
            if (Files.exists(abs)) {
                Path backup = dir.resolve("files").resolve(rel);
                Files.createDirectories(backup.getParent());
                Files.copy(abs, backup, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("created.txt"), rel + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot checkpoint " + rel, e);
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

    /** Restores the files of the last {@code turns} turns that edited files, newest first. */
    public UndoResult undo(String chatId, Path workDir, int turns) {
        Path work = workDir.toAbsolutePath().normalize();
        Path root = root(chatId, work);
        List<String> restored = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        if (!Files.isDirectory(root)) return new UndoResult(0, restored, deleted);
        List<Path> dirs;
        try (Stream<Path> s = Files.list(root)) {
            dirs = s.filter(p -> p.getFileName().toString().matches("\\d+"))
                    .sorted(Comparator.comparingInt((Path p) -> Integer.parseInt(p.getFileName().toString())).reversed())
                    .limit(Math.max(1, turns)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> seenRestored = new HashSet<>();
        for (Path dir : dirs) {
            try {
                Path created = dir.resolve("created.txt");
                if (Files.exists(created)) {
                    for (String rel : Files.readAllLines(created, StandardCharsets.UTF_8)) {
                        if (rel.isBlank()) continue;
                        Files.deleteIfExists(work.resolve(rel));
                        deleted.add(rel);
                    }
                }
                Path files = dir.resolve("files");
                if (Files.isDirectory(files)) {
                    try (Stream<Path> s = Files.walk(files)) {
                        for (Path b : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                            String rel = files.relativize(b).toString().replace('\\', '/');
                            Path target = work.resolve(rel);
                            Files.createDirectories(target.getParent());
                            Files.copy(b, target, StandardCopyOption.REPLACE_EXISTING);
                            if (seenRestored.add(rel)) restored.add(rel);
                        }
                    }
                }
                try (Stream<Path> s = Files.walk(dir)) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot undo " + dir.getFileName(), e);
            }
        }
        touched.remove(chatId);
        current.remove(chatId);
        return new UndoResult(dirs.size(), restored, deleted);
    }
}
