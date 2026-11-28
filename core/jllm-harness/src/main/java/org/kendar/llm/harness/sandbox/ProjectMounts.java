package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The nested mounts that follow the read-write bind mount of a project directory (CLI, docker): what lets the host
 * execute code or change its configuration stays read-only for the container, the rest of the project and git
 * (add, commit, branch, checkout, merge, stash) keep working.
 * <ul>
 *   <li>{@code .git/hooks} and {@code .git/config} (hooks, {@code core.fsmonitor}, {@code core.hooksPath},
 *   {@code core.sshCommand}, filter and diff drivers, aliases), and the same of every submodule git dir under
 *   {@code .git/modules};</li>
 *   <li>{@code .jllm}, with {@code .jllm/scratchpad} read-write on top of it.</li>
 * </ul>
 * A directory missing on the host is covered by a read-only {@code tmpfs}, so the container cannot create one that the
 * host would read next time. A mount point cannot be renamed or removed from inside, so {@code git config} (which
 * renames {@code config.lock}) fails in the container. A {@code .git} that is a file (worktree) is not mounted.
 */
public final class ProjectMounts {

    private static final Set<String> NOT_GIT_DIR_CHILDREN = Set.of("objects", "refs", "logs", "hooks", "info", "lfs");

    private ProjectMounts() {
    }

    /** The git dirs of the submodules, relative to {@code project} ({@code .git/modules/a}, nested ones too). */
    public static List<String> submoduleGitDirs(Path project) {
        List<String> out = new ArrayList<>();
        Path modules = project.resolve(".git").resolve("modules");
        if (!Files.isDirectory(modules, LinkOption.NOFOLLOW_LINKS)) return out;
        Path base = project.toAbsolutePath().normalize();
        try {
            Files.walkFileTree(modules, Set.of(), 12, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    if (a.isSymbolicLink() || a.isOther()) return FileVisitResult.SKIP_SUBTREE;
                    if (!d.equals(modules) && NOT_GIT_DIR_CHILDREN.contains(d.getFileName().toString())
                            && Files.isRegularFile(d.getParent().resolve("HEAD"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (Files.isRegularFile(d.resolve("HEAD"))) {
                        out.add(base.relativize(d.toAbsolutePath().normalize()).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // unreadable: what was found stays
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * The {@code docker create} arguments of the nested mounts, to add after the mount of the project itself.
     *
     * @param project     the project directory on the host
     * @param workspace   where it is mounted in the container, e.g. {@code /workspace}
     * @param mountSource a host path as docker takes it
     */
    public static List<String> args(Path project, String workspace, Function<Path, String> mountSource) {
        List<String> a = new ArrayList<>();
        Path p = project.toAbsolutePath().normalize();
        String ws = workspace.endsWith("/") ? workspace.substring(0, workspace.length() - 1) : workspace;
        if (Files.isDirectory(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            readOnlyDir(a, p, ".git/hooks", ws, mountSource);
            readOnlyFile(a, p, ".git/config", ws, mountSource);
            for (String gd : submoduleGitDirs(p)) {
                readOnlyDir(a, p, gd + "/hooks", ws, mountSource);
                readOnlyFile(a, p, gd + "/config", ws, mountSource);
            }
        }
        if (Files.isDirectory(p.resolve(".jllm"))) {
            bind(a, mountSource.apply(p.resolve(".jllm")), ws + "/.jllm", true);
            if (Files.isDirectory(p.resolve(".jllm").resolve("scratchpad"))) {
                bind(a, mountSource.apply(p.resolve(".jllm").resolve("scratchpad")), ws + "/.jllm/scratchpad", false);
            }
        } else {
            tmpfs(a, ws + "/.jllm");
        }
        return a;
    }

    private static void readOnlyDir(List<String> a, Path p, String rel, String ws, Function<Path, String> src) {
        if (Files.isDirectory(p.resolve(rel))) bind(a, src.apply(p.resolve(rel)), ws + "/" + rel, true);
        else if (!Files.exists(p.resolve(rel), LinkOption.NOFOLLOW_LINKS)) tmpfs(a, ws + "/" + rel);
    }

    private static void readOnlyFile(List<String> a, Path p, String rel, String ws, Function<Path, String> src) {
        if (Files.isRegularFile(p.resolve(rel))) bind(a, src.apply(p.resolve(rel)), ws + "/" + rel, true);
    }

    private static void bind(List<String> a, String src, String dst, boolean readOnly) {
        a.add("--mount");
        a.add("type=bind," + field("src", src) + "," + field("dst", dst) + (readOnly ? ",readonly" : ""));
    }

    private static void tmpfs(List<String> a, String dst) {
        a.add("--tmpfs");
        a.add(dst + ":ro");
    }

    /** A {@code key=value} of {@code --mount}: docker reads the option as CSV, a value with a comma is quoted. */
    private static String field(String key, String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) return key + "=" + value;
        return "\"" + key + "=" + value.replace("\"", "\"\"") + "\"";
    }
}
