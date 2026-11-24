package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** {@link BackupRecordStore} in a {@code backup.json} next to the owner's tarball. */
public final class FileBackupRecordStore implements BackupRecordStore {

    static final String FILE = "backup.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Function<String, Path> dirOf;
    private final Path root;

    /**
     * @param dirOf directory of an owner key ({@code chat/<id>}, {@code user/<id>})
     * @param root  directory {@link #keys()} scans: {@code <root>/<id>/.jllm/backup.json} (chat) and
     *              {@code <root>/.sandbox/<id>/backup.json} (user), see {@link WorkspaceBackups}
     */
    public FileBackupRecordStore(Function<String, Path> dirOf, Path root) {
        this.dirOf = dirOf;
        this.root = root;
    }

    @Override
    public Optional<BackupRecord> get(String ownerKey) {
        Path f = dirOf.apply(ownerKey).resolve(FILE);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(f.toFile(), BackupRecord.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void put(String ownerKey, BackupRecord record) {
        Path dir = dirOf.apply(ownerKey);
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "record", ".tmp");
            JSON.writeValue(tmp.toFile(), record);
            Files.move(tmp, dir.resolve(FILE), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void clear(String ownerKey) {
        try {
            Files.deleteIfExists(dirOf.apply(ownerKey).resolve(FILE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Set<String> keys() {
        Set<String> out = new HashSet<>();
        if (!Files.isDirectory(root)) return out;
        try (var s = Files.list(root)) {
            for (Path d : (Iterable<Path>) s::iterator) {
                String n = d.getFileName().toString();
                if (n.equals(".sandbox")) {
                    try (var u = Files.list(d)) {
                        for (Path ud : (Iterable<Path>) u::iterator) {
                            if (Files.isRegularFile(ud.resolve(FILE))) out.add("user/" + ud.getFileName());
                        }
                    }
                } else if (!n.startsWith(".") && Files.isRegularFile(d.resolve(".jllm").resolve(FILE))) {
                    out.add("chat/" + n);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
