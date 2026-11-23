package org.kendar.llm.harness.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The path jail of the file tools: resolves a model-supplied path inside a root directory and does the I/O on it
 * without following a link that was swapped in after the check.
 * <p>
 * A path is resolved once ({@link #resolve}: lexical check and a real-path check of the deepest existing ancestor),
 * then every operation walks it again, one component at a time, from the real root:
 * <ul>
 *     <li><b>Secure strategy</b> (Linux, macOS: any file system where {@link Files#newDirectoryStream} returns a
 *     {@link SecureDirectoryStream}): each directory is opened relative to its parent with {@code O_NOFOLLOW}
 *     ({@code openat}), the last component is opened, replaced ({@code renameat}) or deleted ({@code unlinkat})
 *     relative to the open parent, again without following a link. A link swapped in at any point makes the open
 *     fail; it can never lead outside the root. NIO has no {@code mkdirat}: a missing directory is created by path and
 *     reopened without following links, so a lost race leaves at worst an empty directory elsewhere.</li>
 *     <li><b>Verify strategy</b> (Windows, or a Unix file system without secure streams): the last component is
 *     opened with {@code NOFOLLOW_LINKS} (on Windows also {@code NOSHARE_DELETE}, so the open file cannot be renamed
 *     or deleted while it is open), then the real path of its directory is checked again and the entry is checked not
 *     to be a link; on a mismatch the file is closed and the call refused. A file the open created is removed again,
 *     and truncation happens only after the check. <b>Residual risk (accepted):</b> a process on the host that swaps a
 *     directory for a junction and back between the open and the re-check can still win the race. NIO can neither pin
 *     intermediate directories on Windows nor compare file identity from an open handle. Creating junctions needs a
 *     host shell, which is unrestricted anyway.</li>
 * </ul>
 * The strategy is picked once per real root, by what the file system supports (logged at DEBUG).
 * <p>
 * Links: a link whose target is inside the root is followed: the walk restarts on the resolved components, again
 * without following links (at most {@value #MAX_LINK_HOPS} hops). A dangling link is refused. A link whose target is
 * outside the root follows the {@link ExternalLinks} mode of the path ({@code files.externalLinks}): {@code NONE}
 * refuses it, {@code READ} follows it for reads and refuses writes (naming the setting), {@code READ_WRITE} follows
 * it. The walk then restarts from the file system root of the target, still without following links on the way; every
 * further hop is judged again. Walkers enter an outside directory link only when reads follow it, once per target.
 * Recursive deletes never follow a link.
 * <p>
 * Hard links: a hard link inside the root to a file outside cannot be told from a normal file by its path. A jailed
 * write never rewrites in place a file with more than one link owned by another user. Windows does not expose the link
 * count through NIO: there the behaviour is unchanged (same-owner and foreign hard links are rewritten in place).
 * Same-owner hard links keep sharing their content.
 */
public final class PathJail {

    private static final Logger log = LoggerFactory.getLogger(PathJail.class);

    static final int MAX_LINK_HOPS = 40;
    static final boolean WINDOWS = File.separatorChar == '\\';
    private static final OpenOption NOSHARE_DELETE = WINDOWS ? noShareDelete() : null;

    /** Test seam: runs right before every final open, create, rename and delete. Null in production. */
    static volatile Runnable beforeOpen;
    /** Test seam: the strategy used for every root; null = by what the file system supports. */
    static volatile Strategy forced;

    enum Strategy {SECURE, VERIFY}

    private static final ConcurrentHashMap<Path, Strategy> STRATEGIES = new ConcurrentHashMap<>();

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

    private PathJail() {
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
    private static void checkExternal(String shown, ExternalLinks links, boolean write) {
        if (links == null || !links.followsOnRead()) throw FileSystemAgentTools.leavesWorkDir(shown);
        if (write && !links.followsOnWrite()) throw FileSystemAgentTools.linkWriteRefused(shown);
    }

    private static List<String> names(Path rel) {
        List<String> out = new ArrayList<>();
        for (Path c : rel) {
            String s = c.toString();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    // ── stat, read ───────────────────────────────────────────────────────────────────────────

    /** Attributes of the file (a final link inside the root followed), or null when it does not exist. */
    public static BasicFileAttributes stat(JailedPath jp) throws IOException {
        return stat(jp, true);
    }

    /** As {@link #stat(JailedPath)}, without following a final link. */
    public static BasicFileAttributes statNoFollow(JailedPath jp) throws IOException {
        return stat(jp, false);
    }

    private static BasicFileAttributes stat(JailedPath jp, boolean followFinal) throws IOException {
        if (jp.components().isEmpty()) return Files.readAttributes(jp.realRoot(), BasicFileAttributes.class);
        try (Located l = locate(jp, false, followFinal, false)) {
            return l.dir().stat(l.name());
        } catch (NoSuchFileException | NotDirectoryException e) {
            return null;
        }
    }

    public static boolean isDirectory(JailedPath jp) throws IOException {
        BasicFileAttributes a = stat(jp);
        return a != null && a.isDirectory();
    }

    /** Opens the file for reading. */
    public static SeekableByteChannel openRead(JailedPath jp) throws IOException {
        try (Located l = locate(jp, false, true, false)) {
            hook();
            return l.dir().open(l.name(), Set.of(StandardOpenOption.READ));
        }
    }

    public static InputStream newInputStream(JailedPath jp) throws IOException {
        return Channels.newInputStream(openRead(jp));
    }

    /** The first {@code max} bytes of the file. */
    public static byte[] readHead(JailedPath jp, int max) throws IOException {
        try (InputStream in = newInputStream(jp)) {
            return in.readNBytes(max);
        }
    }

    /** The whole file; refused with an {@link IOException} above {@code max} bytes. */
    public static byte[] readAllBytes(JailedPath jp, long max) throws IOException {
        try (InputStream in = newInputStream(jp)) {
            byte[] b = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
            if (b.length > max) throw new IOException("File too large (more than " + max + " bytes): " + jp);
            return b;
        }
    }

    // ── write ────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the file for writing; {@code options} may add {@code APPEND}, {@code CREATE}, {@code CREATE_NEW},
     * {@code TRUNCATE_EXISTING}. An existing file with other hard links owned by another user is refused.
     */
    public static SeekableByteChannel openWrite(JailedPath jp, Set<? extends OpenOption> options) throws IOException {
        Set<OpenOption> o = new HashSet<>(options);
        o.add(StandardOpenOption.WRITE);
        try (Located l = locate(jp, false, true, true)) {
            refuseForeignHardLink(l.dir(), l.name(), jp.lexical().toString());
            hook();
            return l.dir().open(l.name(), o);
        }
    }

    /** Creates the missing directories above the file, each one checked as it is entered. */
    public static void createParentDirectories(JailedPath jp) throws IOException {
        if (jp.components().size() < 2) return;
        try (Located ignored = locate(jp, true, true, true)) {
            // the walk created them
        }
    }

    /**
     * Creates the directory; its parent must exist.
     *
     * @throws FileAlreadyExistsException when something (a link included) already has that name
     */
    public static void createDirectory(JailedPath jp) throws IOException {
        if (jp.components().isEmpty()) throw new FileAlreadyExistsException(jp.lexical().toString());
        try (Located l = locate(jp, false, false, true)) {
            if (l.dir().stat(l.name()) != null) throw new FileAlreadyExistsException(jp.lexical().toString());
            hook();
            l.dir().mkdir(l.name());
        }
    }

    /**
     * Deletes the file, link (not its target) or empty directory.
     *
     * @return false when it did not exist
     */
    public static boolean deleteIfExists(JailedPath jp) throws IOException {
        if (jp.components().isEmpty()) throw new IOException("Cannot delete the root: " + jp.lexical());
        try (Located l = locate(jp, false, false, true)) {
            BasicFileAttributes a = l.dir().stat(l.name());
            if (a == null) return false;
            hook();
            l.dir().delete(l.name(), a.isDirectory() && !isLink(a));
            return true;
        } catch (NoSuchFileException | NotDirectoryException e) {
            return false;
        }
    }

    /** Deletes the file, link or directory tree; links inside are removed, never followed. */
    public static void deleteTree(JailedPath jp) throws IOException {
        BasicFileAttributes a = statNoFollow(jp);
        if (a == null) return;
        if (a.isDirectory() && !isLink(a)) {
            for (Entry e : list(jp, false, true)) deleteTree(jp.child(e.name())); // never through a link
        }
        deleteIfExists(jp);
    }

    /**
     * Renames the file, directory or link (not its target) to {@code newName} in the same directory.
     *
     * @throws FileAlreadyExistsException when {@code newName} already exists
     * @throws IllegalArgumentException   when {@code newName} is not a single file name
     */
    public static void rename(JailedPath jp, String newName) throws IOException {
        if (jp.components().isEmpty()) throw new IOException("Cannot rename the root: " + jp.lexical());
        if (newName == null || newName.isEmpty() || newName.equals(".") || newName.equals("..")
                || newName.indexOf('/') >= 0 || newName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid name: " + newName);
        }
        try (Located l = locate(jp, false, false, true)) {
            if (l.dir().stat(l.name()) == null) throw new NoSuchFileException(jp.lexical().toString());
            if (l.dir().stat(newName) != null) throw new FileAlreadyExistsException(newName);
            hook();
            l.dir().rename(l.name(), newName);
        }
    }

    // ── classification (not I/O on content) ──────────────────────────────────────────────────

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

    // ── list, walk ───────────────────────────────────────────────────────────────────────────

    /** A directory entry: its name and attributes read without following a link. */
    public record Entry(String name, BasicFileAttributes attributes) {
        public boolean isLink() {
            return PathJail.isLink(attributes);
        }
    }

    /**
     * The entries of a directory (a link to a directory inside the root followed), sorted by name; each one is
     * stat-ed without following a link.
     *
     * @throws NotDirectoryException when the path is not a directory
     */
    public static List<Entry> list(JailedPath jp) throws IOException {
        return list(jp, true, false);
    }

    private static List<Entry> list(JailedPath jp, boolean followFinal, boolean write) throws IOException {
        List<Entry> out;
        if (jp.components().isEmpty()) {
            try (Dir root = openRoot(strategy(jp.realRoot()), jp.realRoot(), jp.lexical().toString())) {
                out = entries(root);
            }
        } else {
            try (Located l = locate(jp, false, followFinal, write)) {
                BasicFileAttributes a = l.dir().stat(l.name());
                if (a == null) throw new NoSuchFileException(jp.lexical().toString());
                if (!a.isDirectory() || isLink(a)) throw new NotDirectoryException(jp.lexical().toString());
                try (Dir d = l.dir().child(l.name())) {
                    out = entries(d);
                }
            }
        }
        out.sort(Comparator.comparing(Entry::name));
        return out;
    }

    private static List<Entry> entries(Dir d) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (String name : d.names()) {
            BasicFileAttributes a = d.stat(name);
            if (a != null) out.add(new Entry(name, a)); // removed meanwhile: left out
        }
        return out;
    }

    /** What a {@link Visitor} wants after an entry. */
    public enum Action {
        CONTINUE, SKIP_DIRECTORY, STOP
    }

    /** Called for every directory (before its content) and regular file of a walk. */
    @FunctionalInterface
    public interface Visitor {
        Action visit(JailedPath path, BasicFileAttributes attributes) throws IOException;
    }

    /**
     * Walks {@code start} (a file, or a directory and everything below it), directories before their content, names
     * sorted. Every listing and every entry is read through the jail. A link to a file inside the root is visited as
     * that file; a link to a directory inside the root is not entered (its content is walked where it lies). A link
     * leaving the root is followed (file visited, directory entered) only when its {@link ExternalLinks} mode allows
     * reads, and a directory is entered once only, so a link back to an ancestor cannot loop. A dangling link and an
     * entry that vanished or was swapped meanwhile are left out.
     *
     * @return false when a visitor asked to stop
     * @throws NoSuchFileException when {@code start} does not exist
     */
    public static boolean walk(JailedPath start, Visitor visitor) throws IOException {
        BasicFileAttributes a = stat(start);
        if (a == null) throw new NoSuchFileException(start.lexical().toString());
        if (!a.isDirectory()) return visitor.visit(start, a) != Action.STOP;
        Set<Path> entered = new HashSet<>();
        Path real = realOf(start);
        if (real != null && !real.startsWith(start.realRoot())) entered.add(real);
        return walkDirectory(start, a, visitor, entered);
    }

    private static Path realOf(JailedPath p) {
        try {
            return p.lexical().toRealPath();
        } catch (IOException e) {
            return null; // classification only: gone or dangling
        }
    }

    private static boolean walkDirectory(JailedPath dir, BasicFileAttributes attrs, Visitor visitor, Set<Path> entered)
            throws IOException {
        Action action = visitor.visit(dir, attrs);
        if (action == Action.STOP) return false;
        if (action == Action.SKIP_DIRECTORY) return true;
        List<Entry> entries;
        try {
            entries = list(dir);
        } catch (IOException | IllegalArgumentException e) { // vanished, swapped for a link, unreadable
            log.debug("Walk skips {}: {}", dir, e.toString());
            return true;
        }
        for (Entry e : entries) {
            JailedPath child = dir.child(e.name());
            BasicFileAttributes ca = e.attributes();
            if (e.isLink()) {
                try {
                    ca = stat(child); // follows a link inside the jail, and one leaving it when the mode allows
                } catch (IOException | IllegalArgumentException ex) {
                    log.debug("Walk skips link {}: {}", child, ex.toString());
                    continue;
                }
                if (ca == null) continue;
                if (ca.isDirectory()) {
                    Path real = realOf(child);
                    // inside the root: walked where it lies; outside: entered once, never an ancestor of the root
                    if (real == null || real.startsWith(child.realRoot()) || child.realRoot().startsWith(real)
                            || !entered.add(real)) continue;
                }
            }
            if (ca.isDirectory()) {
                if (!walkDirectory(child, ca, visitor, entered)) return false;
            } else if (ca.isRegularFile()) {
                if (visitor.visit(child, ca) == Action.STOP) return false;
            }
        }
        return true;
    }

    /** Whether the attributes (read without following links) are those of a symlink or a Windows junction. */
    public static boolean isLink(BasicFileAttributes a) {
        return a.isSymbolicLink() || (WINDOWS && a.isOther() && a.isDirectory());
    }

    static void hook() {
        Runnable h = beforeOpen;
        if (h != null) h.run();
    }

    // ── hard links ───────────────────────────────────────────────────────────────────────────

    /**
     * Refuses a write in place of a file with more than one link that another user owns: the link may have been
     * planted from a file outside the jail. A hard link to a file of the JVM user keeps sharing. Windows: not checked.
     */
    static void refuseForeignHardLink(Dir dir, String name, String shown) throws IOException {
        if (WINDOWS) return;
        Path p = dir.path().resolve(name);
        try {
            if (!(Files.getAttribute(p, "unix:nlink", LinkOption.NOFOLLOW_LINKS) instanceof Integer n) || n <= 1) return;
            UserPrincipal owner = Files.getOwner(p, LinkOption.NOFOLLOW_LINKS);
            if (!owner.getName().equals(System.getProperty("user.name"))) {
                throw new IOException(shown + " is a hard link to a file owned by another user");
            }
        } catch (UnsupportedOperationException | IllegalArgumentException | NoSuchFileException e) {
            // no link count on this file system, or the file does not exist yet
        }
    }

    // ── the walk ─────────────────────────────────────────────────────────────────────────────

    /** The directory holding the last component (open) and its name. Closing releases the directory. */
    record Located(Dir dir, String name) implements Closeable {
        @Override
        public void close() throws IOException {
            dir.close();
        }
    }

    /**
     * Walks to the parent of the last component. A link whose target is inside the root is followed. A link leaving
     * the root is followed only as {@link ExternalLinks} of the path allows for this access; the walk then restarts
     * from the file system root of the target (still without following links on the way) and is no longer confined.
     *
     * @param createParents create missing directories on the way
     * @param followFinal   follow a link at the last component (false: the link itself, e.g. to delete it)
     * @param write         the access changes something: {@link ExternalLinks#READ} refuses a link leaving the root
     */
    static Located locate(JailedPath jp, boolean createParents, boolean followFinal, boolean write)
            throws IOException {
        String shown = jp.lexical().toString();
        if (jp.components().isEmpty()) throw new FileSystemException(shown, null, "Is a directory");
        List<String> comps = jp.components();
        Path base = jp.realRoot();
        int hops = 0;
        boolean finalFromLink = false;
        while (true) {
            Dir dir = openRoot(strategy(base), base, shown);
            List<String> restart = null;
            Path restartBase = base;
            try {
                for (int i = 0; i < comps.size() && restart == null; i++) {
                    String name = comps.get(i);
                    boolean last = i == comps.size() - 1;
                    BasicFileAttributes a = dir.stat(name);
                    if (a != null && isLink(a) && (!last || followFinal)) {
                        Path at = dir.path().resolve(name);
                        Path target = linkTarget(at, shown);
                        if (!target.equals(at)) { // a Windows reparse point that is not a link resolves to itself
                            if (++hops > MAX_LINK_HOPS) {
                                throw new IllegalArgumentException("Too many levels of links: " + shown);
                            }
                            List<String> inside = insideComponents(target, jp);
                            Path nextBase = jp.realRoot();
                            if (inside == null) {
                                checkExternal(shown, jp.links(), write);
                                nextBase = target.getRoot();
                                if (nextBase == null) throw FileSystemAgentTools.escapes(shown);
                                inside = names(nextBase.relativize(target));
                            }
                            List<String> rest = comps.subList(i + 1, comps.size());
                            finalFromLink |= rest.isEmpty();
                            List<String> next = new ArrayList<>(inside);
                            next.addAll(rest);
                            if (next.isEmpty()) throw new FileSystemException(shown, null, "Is a directory");
                            restart = next;
                            restartBase = nextBase;
                            continue;
                        }
                    }
                    if (last) {
                        if (a == null && finalFromLink) throw new IllegalArgumentException(shown + " is a dangling link");
                        Located l = new Located(dir, name);
                        dir = null; // owned by the result
                        return l;
                    }
                    if (a == null) {
                        if (!createParents) throw new NoSuchFileException(shown);
                        hook();
                        dir.mkdir(name);
                    } else if (!a.isDirectory()) {
                        throw new NotDirectoryException(shown);
                    }
                    Dir next = dir.child(name);
                    dir.close();
                    dir = next;
                }
            } finally {
                if (dir != null) dir.close();
            }
            if (restart == null) throw new IllegalStateException("walk ended without a result: " + shown);
            comps = restart;
            base = restartBase;
        }
    }

    /** The absolute, normalized target of a link (a junction: its real path). */
    private static Path linkTarget(Path at, String shown) {
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
    private static List<String> insideComponents(Path target, JailedPath jp) {
        if (target.startsWith(jp.realRoot())) return names(jp.realRoot().relativize(target));
        if (target.startsWith(jp.root())) return names(jp.root().relativize(target));
        return null;
    }

    static Strategy strategy(Path realRoot) {
        Strategy f = forced;
        if (f != null) return f;
        return STRATEGIES.computeIfAbsent(realRoot, r -> {
            Strategy s;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(r)) {
                s = ds instanceof SecureDirectoryStream ? Strategy.SECURE : Strategy.VERIFY;
            } catch (IOException e) {
                s = Strategy.VERIFY;
            }
            log.debug("Path jail {}: {} strategy", r, s == Strategy.SECURE ? "secure (openat, O_NOFOLLOW)" : "verify");
            return s;
        });
    }

    private static Dir openRoot(Strategy strategy, Path realRoot, String shown) throws IOException {
        if (strategy == Strategy.SECURE) {
            DirectoryStream<Path> ds = Files.newDirectoryStream(realRoot);
            if (ds instanceof SecureDirectoryStream<Path> sds) return new SecureDir(sds, realRoot, shown);
            ds.close();
            throw new IOException("No secure directory stream for " + realRoot);
        }
        return new PathDir(realRoot, realRoot, shown);
    }

    /** {@code com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE} (jdk.unsupported), or null when absent. */
    private static OpenOption noShareDelete() {
        try {
            for (Object o : Class.forName("com.sun.nio.file.ExtendedOpenOption").getEnumConstants()) {
                if (o instanceof OpenOption opt && o instanceof Enum<?> e && "NOSHARE_DELETE".equals(e.name())) return opt;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // boundary: optional JDK-internal option probe, any failure means not available
            log.debug("NOSHARE_DELETE not available: {}", e.toString());
        }
        return null;
    }

    // ── directories ──────────────────────────────────────────────────────────────────────────

    /** An open directory of the walk; names are single components. */
    interface Dir extends Closeable {
        /** The real path of the directory, as walked. */
        Path path();

        /** Attributes without following a link, or null when missing. */
        BasicFileAttributes stat(String name) throws IOException;

        void mkdir(String name) throws IOException;

        Dir child(String name) throws IOException;

        /** The names of the entries of this directory. */
        List<String> names() throws IOException;

        SeekableByteChannel open(String name, Set<? extends OpenOption> options) throws IOException;

        void delete(String name, boolean directory) throws IOException;

        /** Moves {@code from} over {@code to} (replacing it), both in this directory. */
        void rename(String from, String to) throws IOException;

        /** A view of the entry without following a link; null when not supported. */
        <V extends FileAttributeView> V view(String name, Class<V> type);

        @Override
        default void close() throws IOException {
        }
    }

    /** Secure strategy: a directory open as a file descriptor; everything below goes through {@code *at} calls. */
    private static final class SecureDir implements Dir {
        private final SecureDirectoryStream<Path> sds;
        private final Path path;
        private final String shown;

        SecureDir(SecureDirectoryStream<Path> sds, Path path, String shown) {
            this.sds = sds;
            this.path = path;
            this.shown = shown;
        }

        @Override
        public Path path() {
            return path;
        }

        private static Path rel(String name) {
            return Path.of(name);
        }

        @Override
        public BasicFileAttributes stat(String name) throws IOException {
            try {
                return sds.getFileAttributeView(rel(name), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes();
            } catch (NoSuchFileException e) {
                return null;
            }
        }

        @Override
        public void mkdir(String name) throws IOException {
            // no mkdirat in NIO: by path, then reopened without following links (child). A won race leaves an empty
            // directory elsewhere; nothing is written into it.
            try {
                Files.createDirectory(path.resolve(name));
            } catch (FileAlreadyExistsException e) {
                // created meanwhile: child() checks what it is
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public Dir child(String name) throws IOException {
            try {
                DirectoryStream<Path> ds = sds.newDirectoryStream(rel(name), LinkOption.NOFOLLOW_LINKS);
                return new SecureDir((SecureDirectoryStream<Path>) ds, path.resolve(name), shown);
            } catch (NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                BasicFileAttributes a = stat(name);
                if (a != null && a.isSymbolicLink()) throw FileSystemAgentTools.escapes(shown); // swapped for a link
                throw e;
            }
        }

        @Override
        public List<String> names() {
            List<String> out = new ArrayList<>();
            for (Path p : sds) out.add(p.getFileName().toString());
            return out;
        }

        @Override
        public SeekableByteChannel open(String name, Set<? extends OpenOption> options) throws IOException {
            Set<OpenOption> o = new HashSet<>(options);
            o.add(LinkOption.NOFOLLOW_LINKS);
            try {
                return sds.newByteChannel(rel(name), o);
            } catch (FileSystemException e) {
                if (e instanceof NoSuchFileException || e instanceof FileAlreadyExistsException) throw e;
                BasicFileAttributes a = stat(name);
                if (a != null && a.isSymbolicLink()) throw FileSystemAgentTools.escapes(shown); // swapped for a link
                throw e;
            }
        }

        @Override
        public void delete(String name, boolean directory) throws IOException {
            if (directory) sds.deleteDirectory(rel(name));
            else sds.deleteFile(rel(name));
        }

        @Override
        public void rename(String from, String to) throws IOException {
            sds.move(rel(from), sds, rel(to));
        }

        @Override
        public <V extends FileAttributeView> V view(String name, Class<V> type) {
            try {
                return sds.getFileAttributeView(rel(name), type, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException e) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            sds.close();
        }
    }

    /**
     * Verify strategy: a directory by path. Links are not followed at the last component and the directory is checked
     * to still be inside the real root after each open.
     */
    private static final class PathDir implements Dir {
        private final Path path;
        private final Path realRoot;
        private final String shown;

        PathDir(Path path, Path realRoot, String shown) {
            this.path = path;
            this.realRoot = realRoot;
            this.shown = shown;
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public BasicFileAttributes stat(String name) throws IOException {
            try {
                return Files.readAttributes(path.resolve(name), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                return null;
            }
        }

        @Override
        public void mkdir(String name) throws IOException {
            try {
                Files.createDirectory(path.resolve(name));
            } catch (FileAlreadyExistsException e) {
                // created meanwhile
            }
            verifyDir(path.resolve(name));
        }

        @Override
        public Dir child(String name) {
            return new PathDir(path.resolve(name), realRoot, shown);
        }

        @Override
        public List<String> names() throws IOException {
            List<String> out = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                for (Path p : ds) out.add(p.getFileName().toString());
            }
            verifyDir(path); // listed where the walk found it
            return out;
        }

        /** The directory still is where the walk found it: inside the real root. */
        private void verifyDir(Path dir) throws IOException {
            if (!dir.toRealPath().startsWith(realRoot)) throw FileSystemAgentTools.escapes(shown);
        }

        private void verify(String name) throws IOException {
            verifyDir(path);
            BasicFileAttributes a = Files.readAttributes(path.resolve(name), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (isLink(a)) throw FileSystemAgentTools.escapes(shown);
        }

        @Override
        public SeekableByteChannel open(String name, Set<? extends OpenOption> options) throws IOException {
            Path p = path.resolve(name);
            Set<OpenOption> o = new HashSet<>(options);
            o.add(LinkOption.NOFOLLOW_LINKS);
            if (NOSHARE_DELETE != null) o.add(NOSHARE_DELETE);
            boolean truncate = o.remove(StandardOpenOption.TRUNCATE_EXISTING); // only after the check
            boolean created = o.contains(StandardOpenOption.CREATE_NEW);
            SeekableByteChannel ch;
            if (o.remove(StandardOpenOption.CREATE) && !created) {
                try {
                    ch = Files.newByteChannel(p, o);
                } catch (NoSuchFileException e) {
                    o.add(StandardOpenOption.CREATE_NEW);
                    ch = Files.newByteChannel(p, o);
                    created = true;
                }
            } else {
                ch = Files.newByteChannel(p, o);
            }
            try {
                verify(name);
            } catch (IOException | RuntimeException e) { // boundary: close and clean up on any failure, rethrown unchanged
                ch.close();
                if (created) {
                    try {
                        Files.deleteIfExists(p); // created where the swapped link pointed: remove it again
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
                throw e;
            }
            if (truncate) ch.truncate(0);
            return ch;
        }

        @Override
        public void delete(String name, boolean directory) throws IOException {
            verifyDir(path);
            Files.delete(path.resolve(name));
        }

        @Override
        public void rename(String from, String to) throws IOException {
            verifyDir(path);
            try {
                Files.move(path.resolve(from), path.resolve(to), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(path.resolve(from), path.resolve(to), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public <V extends FileAttributeView> V view(String name, Class<V> type) {
            return Files.getFileAttributeView(path.resolve(name), type, LinkOption.NOFOLLOW_LINKS);
        }
    }
}
