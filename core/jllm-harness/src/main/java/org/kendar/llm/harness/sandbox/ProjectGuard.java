package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The after-the-fact protection of what makes the host execute code or change its configuration: {@code .git/config},
 * {@code .git/hooks/**}, the same of the submodules ({@code .git/modules/*&#47;{config,hooks}}) and {@code .jllm/**}
 * (without {@code scratchpad}, {@code history} and {@code checkpoints}, which the program writes itself). A snapshot
 * is taken before a command runs in a container; afterwards {@link #restore} puts back whatever changed (changed or
 * deleted files, files and directories that appeared) and reports it. Symbolic links are never followed.
 */
public final class ProjectGuard {

    private static final Set<String> RUNTIME = Set.of(".jllm/scratchpad", ".jllm/history", ".jllm/checkpoints");

    private record Entry(char type, byte[] data, String target) {
        boolean same(Entry o) {
            return o != null && type == o.type && Arrays.equals(data, o.data)
                    && java.util.Objects.equals(target, o.target);
        }
    }

    private final Path root;
    private final List<String> roots;
    private final Map<String, Entry> before;

    private ProjectGuard(Path root, List<String> roots, Map<String, Entry> before) {
        this.root = root;
        this.roots = roots;
        this.before = before;
    }

    /** The protected paths of a project, relative and with {@code /}: files and directories (walked in full). */
    public static List<String> protectedRoots(Path project) {
        List<String> r = new ArrayList<>();
        Path p = project.toAbsolutePath().normalize();
        if (Files.isDirectory(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            r.add(".git/config");
            r.add(".git/hooks");
            for (String gd : ProjectMounts.submoduleGitDirs(p)) {
                r.add(gd + "/config");
                r.add(gd + "/hooks");
            }
        }
        r.add(".jllm");
        return r;
    }

    /** Records the protected content of {@code project} as it is now. */
    public static ProjectGuard snapshot(Path project) {
        Path p = project.toAbsolutePath().normalize();
        List<String> roots = protectedRoots(p);
        return new ProjectGuard(p, roots, scan(p, roots));
    }

    /**
     * Compares with the snapshot and puts back what differs.
     *
     * @return the relative paths that were changed (restored, or removed when they are new); empty when nothing was
     */
    public List<String> restore() {
        Map<String, Entry> now = scan(root, roots);
        List<String> changed = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        for (String k : now.keySet()) if (!before.containsKey(k)) extra.add(k);
        extra.sort(Collections.reverseOrder()); // children before their directory
        for (String k : extra) {
            changed.add(k);
            try {
                Files.deleteIfExists(root.resolve(k));
            } catch (IOException e) {
                changed.set(changed.size() - 1, k + " (not removed: " + e.getMessage() + ")");
            }
        }
        for (var e : before.entrySet()) {
            String k = e.getKey();
            if (e.getValue().same(now.get(k))) continue;
            changed.add(k);
            try {
                put(k, e.getValue(), now.get(k));
            } catch (IOException ex) {
                changed.set(changed.size() - 1, k + " (not restored: " + ex.getMessage() + ")");
            }
        }
        return changed;
    }

    private void put(String rel, Entry want, Entry have) throws IOException {
        Path f = root.resolve(rel);
        if (have != null && have.type != want.type) Files.delete(f);
        switch (want.type) {
            case 'D' -> {
                if (!Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(f);
            }
            case 'L' -> {
                Files.deleteIfExists(f);
                Files.createSymbolicLink(f, Path.of(want.target));
            }
            default -> {
                if (f.getParent() != null) Files.createDirectories(f.getParent());
                Files.write(f, want.data);
            }
        }
    }

    private static Map<String, Entry> scan(Path root, List<String> roots) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (String r : roots) {
            Path start = root.resolve(r);
            if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                Files.walkFileTree(start, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                        String rel = rel(root, d);
                        if (RUNTIME.contains(rel)) return FileVisitResult.SKIP_SUBTREE;
                        out.put(rel, new Entry('D', null, null));
                        return a.isSymbolicLink() || a.isOther() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                        String rel = rel(root, f);
                        if (a.isSymbolicLink()) out.put(rel, new Entry('L', null, String.valueOf(Files.readSymbolicLink(f))));
                        else if (a.isRegularFile()) out.put(rel, new Entry('F', Files.readAllBytes(f), null));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path f, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                // unreadable: compared as missing
            }
        }
        return out;
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
