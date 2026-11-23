package org.kendar.llm.harness.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotLinkException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;

/**
 * The security-critical half of the path jail: turns a model-supplied string into a {@link JailedPath} and judges
 * links. It does no I/O on file content; {@link PathJail} (the thin I/O layer) only ever takes {@link JailedPath}s
 * produced here and re-walks them one component at a time.
 * <p>
 * Invariants:
 * <ol>
 *     <li><b>Lexical confinement.</b> {@link #resolve} normalizes {@code root + path} (so {@code ..}, {@code .},
 *     repeated and mixed separators are gone) and refuses a result not below the root. An absolute path is accepted
 *     only if it lies below the root.</li>
 *     <li><b>Components are plain names.</b> A {@link JailedPath} holds no {@code .}, {@code ..} or empty component;
 *     the I/O layer opens each one relative to its parent, never as a multi-segment string.</li>
 *     <li><b>Early link check.</b> The deepest existing ancestor is resolved with {@code toRealPath}; if it leaves the
 *     real root the {@link ExternalLinks} mode decides (refuse, read-only, read-write). A not-yet-existing tail is
 *     judged by its existing ancestor, so a missing file under a symlinked directory is not a loophole.</li>
 *     <li><b>Links are judged on their text, never trusted.</b> {@link #followLink} classifies a link target as inside
 *     or outside the root; the caller then re-walks the resulting components without following links, so a link swapped
 *     in later cannot be reached. Chains are bounded by {@value #MAX_LINK_HOPS} hops (loops are refused).</li>
 *     <li><b>Classification only.</b> {@link #realComponents}, {@link #realPath} and {@link #realKey} are for callers
 *     that hide or key parts of the tree; no I/O is done through their results.</li>
 *     <li><b>Hard links.</b> {@link #refuseForeignHardLink} refuses an in-place write to a multi-link file owned by
 *     another user (not checkable on Windows).</li>
 * </ol>
 */
public final class PathResolver {

    static final int MAX_LINK_HOPS = 40;
    static final boolean WINDOWS = File.separatorChar == '\\';

    private PathResolver() {
    }

    /**
     * A path inside a jail.
     *
     * @param root       the jail root, absolute and normalized (not resolved)
     * @param realRoot   its real path
     * @param components the names from the root to the file, normalized (no {@code .} or {@code ..}); empty for the root
     * @param lexical    root + components: what is shown and used for checkpoint and quota keys
     * @param links      what a link that leaves the root does ({@link ExternalLinks}); links inside are always followed
     */
    public record JailedPath(Path root, Path realRoot, List<String> components, Path lexical,
                              ExternalLinks links) {
        public JailedPath {
            if (links == null) links = ExternalLinks.NONE;
            components = List.copyOf(components);
        }

        /** The last component, or "" for the root. */
        public String fileName() {
            return components.isEmpty() ? "" : components.get(components.size() - 1);
        }

        /** The entry {@code name} of this directory. */
        public JailedPath child(String name) {
            List<String> c = new ArrayList<>(components);
            c.add(name);
            return new JailedPath(root, realRoot, c, lexical.resolve(name), links);
        }

        /** The directory holding this path; the root has none. */
        public JailedPath parent() {
            if (components.isEmpty()) return null;
            return new JailedPath(root, realRoot, components.subList(0, components.size() - 1), lexical.getParent(),
                    links);
        }

        @Override
        public String toString() {
            return lexical.toString();
        }
    }

    /** Where a followed link sends the walk: restart at {@code base} (a real directory) with these components. */
    record Hop(List<String> components, Path base) {
    }

    // ── resolve ──────────────────────────────────────────────────────────────────────────────

    /**
     * Resolves {@code path} (relative to {@code rootDir}, or absolute) and refuses one that leaves the root lexically,
     * or through a link of an existing ancestor unless {@code links} lets the operation follow it.
     *
     * @param links the policy for a link whose target is outside the root ({@code files.externalLinks}); a link
     *              inside is always followed
     * @param write true when the path is about to be written: {@link ExternalLinks#READ} then refuses it as well
     * @throws IllegalArgumentException with {@link FileSystemAgentTools#escapes},
     *                                  {@link FileSystemAgentTools#leavesWorkDir} or
     *                                  {@link FileSystemAgentTools#linkWriteRefused}
     */
    public static JailedPath resolve(Path rootDir, String path, ExternalLinks links, boolean write) {
        Path root = rootDir.toAbsolutePath().normalize();
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) throw FileSystemAgentTools.escapes(path);
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve path: " + path, e);
        }
        JailedPath jp = new JailedPath(root, realRoot, names(root.relativize(resolved)), resolved, links);
        // early check: the deepest existing ancestor (links resolved) must be inside
        try {
            Path existing = resolved;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            if (existing != null && !existing.toRealPath().startsWith(realRoot)) checkExternal(path, links, write);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve path: " + path, e);
        }
        return jp;
    }

    /** Refuses a link that leaves the root when {@code links} does not allow it for this kind of access. */
    static void checkExternal(String shown, ExternalLinks links, boolean write) {
        if (links == null || !links.followsOnRead()) throw FileSystemAgentTools.leavesWorkDir(shown);
        if (write && !links.followsOnWrite()) throw FileSystemAgentTools.linkWriteRefused(shown);
    }

    static List<String> names(Path rel) {
        List<String> out = new ArrayList<>();
        for (Path c : rel) {
            String s = c.toString();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    // ── links ────────────────────────────────────────────────────────────────────────────────

    /** Whether the attributes (read without following links) are those of a symlink or a Windows junction. */
    public static boolean isLink(BasicFileAttributes a) {
        return a.isSymbolicLink() || (WINDOWS && a.isOther() && a.isDirectory());
    }

    /**
     * Decides where the link {@code at} (component {@code i} of {@code comps}) sends the walk. Inside the root: restart
     * from the real root. Outside: judged by {@link #checkExternal}, restart from the file system root of the target.
     * The caller re-walks the returned components without following links.
     *
     * @param hops the links already followed in this walk
     * @return the restart point, or null when the entry resolves to itself (a Windows reparse point that is not a link)
     * @throws IllegalArgumentException when the chain is too long, the link dangles or leaves the root unallowed
     */
    static Hop followLink(Path at, List<String> comps, int i, JailedPath jp, boolean write, int hops, String shown)
            throws IOException {
        Path target = linkTarget(at, shown);
        if (target.equals(at)) return null;
        if (hops + 1 > MAX_LINK_HOPS) throw new IllegalArgumentException("Too many levels of links: " + shown);
        List<String> inside = insideComponents(target, jp);
        Path nextBase = jp.realRoot();
        if (inside == null) {
            checkExternal(shown, jp.links(), write);
            nextBase = target.getRoot();
            if (nextBase == null) throw FileSystemAgentTools.escapes(shown);
            inside = names(nextBase.relativize(target));
        }
        List<String> next = new ArrayList<>(inside);
        next.addAll(comps.subList(i + 1, comps.size()));
        if (next.isEmpty()) throw new FileSystemException(shown, null, "Is a directory");
        return new Hop(next, nextBase);
    }

    /** The absolute, normalized target of a link (a junction: its real path). */
    static Path linkTarget(Path at, String shown) {
        try {
            Path t = Files.readSymbolicLink(at);
            return at.getParent().resolve(t).normalize();
        } catch (NotLinkException | UnsupportedOperationException e) {
            try {
                return at.toRealPath();
            } catch (IOException e2) {
                throw new IllegalArgumentException(shown + " is a dangling link");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve path: " + shown, e);
        }
    }

    /**
     * The components of {@code target} below the root, or null when it is outside. Only classifies, on the text of the
     * link: the walk that follows opens the components again without following links, so the target can never be
     * reached through a link swapped in meanwhile.
     */
    static List<String> insideComponents(Path target, JailedPath jp) {
        if (target.startsWith(jp.realRoot())) return names(jp.realRoot().relativize(target));
        if (target.startsWith(jp.root())) return names(jp.root().relativize(target));
        return null;
    }

    // ── classification (no I/O through the results) ──────────────────────────────────────────

    /**
     * Where the path lands now: the components below the real root after following the links of its existing part
     * (a missing tail is appended as written), or null when it leaves the root. A classification for callers that
     * hide part of the tree (jllm-web hides {@code .jllm}); the I/O itself is still checked by the walk.
     */
    public static List<String> realComponents(JailedPath jp) {
        Path existing = jp.lexical();
        while (existing != null && !Files.exists(existing)) existing = existing.getParent(); // a dangling link: kept as written
        if (existing == null) return null;
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            return null;
        }
        if (!real.startsWith(jp.realRoot())) return null;
        List<String> out = names(jp.realRoot().relativize(real));
        out.addAll(names(existing.relativize(jp.lexical())));
        return out;
    }

    /**
     * The real path of a host path whose ancestors may be links (the deepest existing ancestor resolved, the rest
     * appended). A classification of paths the harness itself builds; no I/O is done through the result.
     */
    public static Path realKey(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return abs;
        try {
            return existing.toRealPath().resolve(existing.relativize(abs)).normalize();
        } catch (IOException e) {
            return abs;
        }
    }

    /** {@link #realKey} of a jailed path. */
    public static Path realPath(JailedPath jp) {
        return realKey(jp.lexical());
    }

    // ── hard links ───────────────────────────────────────────────────────────────────────────

    /**
     * Refuses a write in place of a file with more than one link that another user owns: the link may have been
     * planted from a file outside the jail. A hard link to a file of the JVM user keeps sharing. Windows: not checked.
     *
     * @param file  the entry about to be written (it may not exist yet)
     * @param shown the name used in the message
     */
    static void refuseForeignHardLink(Path file, String shown) throws IOException {
        if (WINDOWS) return;
        try {
            if (!(Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) instanceof Integer n) || n <= 1) return;
            UserPrincipal owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS);
            if (!owner.getName().equals(System.getProperty("user.name"))) {
                throw new IOException(shown + " is a hard link to a file owned by another user");
            }
        } catch (UnsupportedOperationException | IllegalArgumentException | NoSuchFileException e) {
            // no link count on this file system, or the file does not exist yet
        }
    }
}
