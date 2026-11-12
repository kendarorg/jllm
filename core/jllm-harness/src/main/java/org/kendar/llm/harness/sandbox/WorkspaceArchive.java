package org.kendar.llm.harness.sandbox;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The zip format of the exec daemon's {@code GET /backup} and {@code POST /setup}: the workspace as regular files and
 * directories (symbolic links are skipped), plus a {@value #EXEC_MANIFEST} entry listing the executable files, because
 * a zip written by the JDK cannot carry permissions. {@code .kendhar/scratchpad} and {@code .kendhar/history} are never
 * packed or unpacked: the scratchpad is disposable and the history stays on the host.
 */
public final class WorkspaceArchive {

    static final String EXEC_MANIFEST = ".jllm-exec";
    private static final List<String> EXCLUDED = List.of(".kendhar/scratchpad", ".kendhar/history");

    private WorkspaceArchive() {
    }

    static boolean excluded(String rel) {
        for (String e : EXCLUDED) if (rel.equals(e) || rel.startsWith(e + "/")) return true;
        return false;
    }

    /** True when {@code dir} holds anything worth uploading (more than the harness's own excluded data). */
    public static boolean hasContent(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        boolean[] any = {false};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                String rel = rel(dir, d);
                return !rel.isEmpty() && excluded(rel) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (a.isRegularFile() && !excluded(rel(dir, f))) {
                    any[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return any[0];
    }

    /** Zips {@code dir}. */
    public static byte[] pack(Path dir) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<String> execs = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                    String rel = rel(dir, d);
                    if (rel.isEmpty()) return FileVisitResult.CONTINUE;
                    if (excluded(rel)) return FileVisitResult.SKIP_SUBTREE;
                    zip.putNextEntry(new ZipEntry(rel + "/"));
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    String rel = rel(dir, f);
                    if (!a.isRegularFile() || excluded(rel)) return FileVisitResult.CONTINUE; // links and devices
                    zip.putNextEntry(new ZipEntry(rel));
                    Files.copy(f, zip);
                    zip.closeEntry();
                    if (Files.isExecutable(f) && posix(f)) execs.add(rel);
                    return FileVisitResult.CONTINUE;
                }
            });
            zip.putNextEntry(new ZipEntry(EXEC_MANIFEST));
            zip.write(String.join("\n", execs).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return bytes.toByteArray();
    }

    /** Unpacks a zip made by {@link #pack} or by the daemon into {@code dir}, overwriting; returns the file count. */
    public static int unpack(byte[] zipBytes, Path dir) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Set<String> execs = new HashSet<>();
        List<String> written = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.equals(EXEC_MANIFEST)) {
                    for (String l : new String(zip.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                        if (!l.isBlank()) execs.add(l.strip());
                    }
                    continue;
                }
                Path target = root.resolve(name).normalize();
                if (name.startsWith("/") || !target.startsWith(root) || excluded(name.replaceAll("/$", ""))) {
                    throw new IOException("Refusing archive entry " + name);
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                if (Files.isSymbolicLink(target)) Files.delete(target);
                copy(zip, target);
                written.add(name);
            }
        }
        for (String name : written) {
            if (execs.contains(name)) setExecutable(root.resolve(name));
        }
        return written.size();
    }

    private static void copy(InputStream in, Path target) throws IOException {
        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean posix(Path f) {
        return f.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static void setExecutable(Path f) throws IOException {
        if (!posix(f)) return;
        Set<PosixFilePermission> p = new HashSet<>(Files.getPosixFilePermissions(f, LinkOption.NOFOLLOW_LINKS));
        p.add(PosixFilePermission.OWNER_EXECUTE);
        p.add(PosixFilePermission.GROUP_EXECUTE);
        p.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(f, p);
    }

    private static String rel(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }
}
