package org.kendar.llm.harness.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FolderTrustTest {

    @TempDir
    Path tmp;

    private Path project() throws Exception {
        Path p = Files.createDirectories(tmp.resolve("project"));
        Files.createDirectories(p.resolve(".jllm"));
        Files.writeString(p.resolve(".jllm/settings.json"), "{}");
        return p;
    }

    @Test
    void hashChangesWithContentAndIgnoresRuntimeDirs() throws Exception {
        Path p = project();
        String h = FolderTrust.hashOf(p);
        assertEquals(h, FolderTrust.hashOf(p));
        for (String d : FolderTrust.RUNTIME_DIRS) {
            Files.createDirectories(p.resolve(".jllm").resolve(d));
            Files.writeString(p.resolve(".jllm").resolve(d).resolve("x"), "runtime");
        }
        assertEquals(h, FolderTrust.hashOf(p));
        Files.writeString(p.resolve(".jllm/settings.json"), "{\"a\":1}");
        assertNotEquals(h, FolderTrust.hashOf(p));
        String h2 = FolderTrust.hashOf(p);
        Files.writeString(p.resolve(".jllm/new.md"), "x");
        assertNotEquals(h2, FolderTrust.hashOf(p));
    }

    @Test
    void symlinkIsHashedByTargetTextNotFollowed() throws Exception {
        Path p = project();
        Path outside = Files.writeString(tmp.resolve("outside.txt"), "one");
        try {
            Files.createSymbolicLink(p.resolve(".jllm/link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here");
        }
        String h = FolderTrust.hashOf(p);
        Files.writeString(outside, "two");
        assertEquals(h, FolderTrust.hashOf(p), "the target content is not part of the hash");
    }

    @Test
    void trustRecordsHashTimeAndLoosenings() throws Exception {
        Path p = project();
        FileFolderTrust t = new FileFolderTrust(tmp.resolve("home/.jllm/trusted.json"));
        assertFalse(t.isTrusted(p));
        t.trust(p, Set.of("permissions.allow"));
        assertTrue(t.isTrusted(p));
        var r = t.record(p).orElseThrow();
        assertEquals(FolderTrust.projectKey(p), r.path());
        assertEquals(Set.of("permissions.allow"), r.loosenings());
        assertTrue(r.trustedAt() > 0);
        t.approveLoosening(p, "sandbox.backend");
        assertEquals(Set.of("permissions.allow", "sandbox.backend"), t.record(p).orElseThrow().loosenings());
        Files.writeString(p.resolve(".jllm/settings.json"), "{\"changed\":true}");
        assertFalse(t.isTrusted(p), "a changed .jllm asks again");
        t.approveLoosening(p, "web.fetchMaxBytes"); // not recorded for a stale hash
        assertFalse(t.record(p).orElseThrow().loosenings().contains("web.fetchMaxBytes"));
        t.trust(p, Set.of());
        assertTrue(new FileFolderTrust(tmp.resolve("home/.jllm/trusted.json")).isTrusted(p));
        assertFalse(new FileFolderTrust(tmp.resolve("home/.jllm/trusted.json")).isTrusted(tmp));
    }
}
