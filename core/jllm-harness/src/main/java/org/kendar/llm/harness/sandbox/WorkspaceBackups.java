package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Backups of container workspaces: the zip of the daemon's {@code GET /backup} (the tarball) and a {@code docker
 * commit} image, with a {@link BackupRecord} per owner kept in a {@link BackupRecordStore}.
 * <p>
 * Tarball locations (web): {@code <layout>/<chatId>/.jllm/workspace-backup.zip} for a chat, and
 * {@code <layout>/.sandbox/<userId>/workspace-backup.zip} for a user (a dot directory, never a chat). CLI: the
 * workspace is unpacked into the project directory (only when the docker daemon is remote; otherwise the project is
 * mounted and there is nothing to back up).
 */
final class WorkspaceBackups {

    /** What a backup did; {@code ok} false when the container's files may not be safe on the host. */
    record Backup(boolean ok, String message) {
    }

    static final String TARBALL = "workspace-backup.zip";
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(WorkspaceBackups.class);

    private final Layout layout;
    private final Kind kind;
    private final Path startupDir;
    private final BackupRecordStore store;

    WorkspaceBackups(Layout layout, Kind kind, Path startupDir, BackupRecordStore store) {
        this.layout = layout;
        this.kind = kind;
        this.startupDir = startupDir;
        this.store = store != null ? store : new FileBackupRecordStore(this::dir, layout.location());
    }

    BackupRecordStore store() {
        return store;
    }

    /** Directory holding the tarball and the record of an owner key ({@code chat/<id>} or {@code user/<id>}). */
    Path dir(String ownerKey) {
        int slash = ownerKey.indexOf('/');
        String scope = ownerKey.substring(0, slash);
        String id = ownerKey.substring(slash + 1);
        if (scope.equals("user")) return layout.location().resolve(".sandbox").resolve(Placement.safe(id));
        return Layout.resolveJailed(layout.location().toString(), id).resolve(".jllm");
    }

    Path tarball(String ownerKey) {
        return dir(ownerKey).resolve(TARBALL);
    }

    /** The zip to start a new container from: the project (CLI) or the last backup (web); null: none. */
    byte[] initialWorkspace(Placement p, boolean remote) throws IOException {
        if (kind == Kind.CLI) {
            return remote && WorkspaceArchive.hasContent(startupDir) ? WorkspaceArchive.pack(startupDir) : null;
        }
        Path f = tarball(p.ownerKey());
        return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
    }

    /** The committed image to create the container from: only when it was made on this docker host and still exists. */
    String usableImage(Placement p, DockerEndpoint ep) {
        if (kind != Kind.WEB) return null;
        Optional<BackupRecord> r = store.get(p.ownerKey());
        if (r.isEmpty() || r.get().image() == null) return null;
        return r.get().dockerHost().equals(ep.key()) && ep.imageExists(r.get().image()) ? r.get().image() : null;
    }

    static String imageName(Kind kind, String ownerKey) {
        return "jllm-backup/" + kind.label() + "-" + ownerKey.replace('/', '-').toLowerCase(Locale.ROOT);
    }

    /**
     * Downloads the container's workspace into the project directory (CLI) or the owner's tarball (web) and commits an
     * image. Refused, with the container kept, when the workspace exceeds {@code cleanup.maxWorkspaceBytes}.
     */
    Backup backup(DaemonClient client, String containerName, DockerEndpoint ep, Placement p) {
        try {
            DaemonClient.Response r = client.call("GET", "/backup", null, null, null, TRANSFER_TIMEOUT);
            if (r.status() != 200) return new Backup(false, "Backup failed: " + r.status() + " " + r.text());
            long quota = p.docker().cleanup().maxWorkspaceBytes();
            if (quota > 0) {
                long size = WorkspaceArchive.totalBytes(r.body());
                if (size > quota) {
                    return new Backup(false, "Backup refused: the workspace of " + containerName + " is " + size / 1024
                            + " KB, over the limit of " + quota / 1024 + " KB.");
                }
            }
            if (kind == Kind.CLI) {
                int files = WorkspaceArchive.unpack(r.body(), startupDir);
                return new Backup(true, "Backed up " + files + " file(s) from " + containerName + " into " + startupDir + ".");
            }
            String owner = p.ownerKey();
            Path f = tarball(owner);
            Files.createDirectories(f.getParent());
            Path tmp = Files.createTempFile(f.getParent(), "backup", ".tmp");
            Files.write(tmp, r.body());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            String image = commit(ep, containerName, owner);
            long now = System.currentTimeMillis();
            int days = p.docker().cleanup().retentionDays();
            store.put(owner, new BackupRecord(image, f.toAbsolutePath().toString(), ep.key(), now,
                    days > 0 ? now + days * 86_400_000L : 0, p.userId()));
            return new Backup(true, "Backed up " + r.body().length / 1024 + " KB from " + containerName + " to " + f
                    + (image == null ? "." : " (image " + image + ")."));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Backup(false, "Backup interrupted.");
        } catch (IOException | RuntimeException e) { // boundary: reported to the caller as a failed backup
            return new Backup(false, "Backup failed: " + e.getMessage());
        }
    }

    /** {@code docker commit} of the container; null when it failed (the tarball is enough to restore). */
    private String commit(DockerEndpoint ep, String containerName, String ownerKey) {
        String image = imageName(kind, ownerKey);
        try {
            DockerCommandRunner.Result r = ep.run("commit", "--change", "ENV JLLM_TOKEN=", containerName, image);
            if (r.exitCode() == 0) return image;
            log.warn("docker commit of {} failed: {}", containerName, r.stderr().strip());
        } catch (IOException e) {
            log.warn("docker commit of {} failed: {}", containerName, e.getMessage());
        }
        return null;
    }

    /** Deletes the tarball, the image and the record of an owner. */
    void expire(String ownerKey, Function<String, DockerEndpoint> endpointOf) {
        Optional<BackupRecord> r = store.get(ownerKey);
        r.ifPresent(rec -> {
            if (rec.image() != null) endpointOf.apply(rec.dockerHost()).removeImage(rec.image());
        });
        try {
            Files.deleteIfExists(tarball(ownerKey));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        store.clear(ownerKey);
    }

    /** Expires every record whose retention is over. Returns the owner keys. */
    List<String> expireDue(long now, Function<String, DockerEndpoint> endpointOf) {
        List<String> done = new ArrayList<>();
        for (String key : store.keys()) {
            Optional<BackupRecord> r = store.get(key);
            if (r.isPresent() && r.get().expiresAt() > 0 && r.get().expiresAt() <= now) {
                expire(key, endpointOf);
                done.add(key);
            }
        }
        return done;
    }
}
