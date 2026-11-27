package org.kendar.llm.eval.pin;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real git in a temporary repository; skipped when git is not installed. */
@Tag("unit")
class BuildInfoTest {

    @TempDir
    Path tmp;

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com",
                "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), String.join(" ", cmd) + ": " + out);
    }

    private Path repo() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve("sub"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        Files.writeString(repo.resolve("sub/b.txt"), "two\n");
        Files.createDirectories(repo.resolve("eval-results"));
        Files.writeString(repo.resolve("eval-results/kept.json"), "{}\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "first");
        return repo;
    }

    @Test
    void aCleanTreeHasACommitAndNoDirtyFiles() throws Exception {
        Path repo = repo();
        BuildInfo b = BuildInfo.detect(repo.resolve("sub"), repo.resolve("eval-results"), "1.0");
        assertEquals(40, b.commit().length(), b.commit());
        assertFalse(b.dirty());
        assertEquals(0, b.dirtyFileCount());
        assertEquals("none", b.diffHash());
        assertEquals("1.0", b.projectVersion());
        assertEquals(System.getProperty("java.version"), b.javaVersion());
        assertFalse(b.branch().isBlank());
        assertFalse(b.describe().isBlank());
        assertEquals(b.commit().substring(0, 8), b.shortCommit());
        assertEquals(b.shortCommit(), b.label());
    }

    @Test
    void aModifiedTrackedFileMakesTheTreeDirtyAndChangesTheDiffHash() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("a.txt"), "changed\n");
        BuildInfo b = BuildInfo.detect(repo, repo.resolve("eval-results"), "1.0");
        assertTrue(b.dirty());
        assertEquals(1, b.dirtyFileCount());
        assertEquals(List.of("M a.txt"), b.dirtyFiles());
        assertEquals(64, b.diffHash().length());
        assertTrue(b.label().endsWith("+dirty"));

        Files.writeString(repo.resolve("a.txt"), "changed again\n");
        BuildInfo c = BuildInfo.detect(repo, repo.resolve("eval-results"), "1.0");
        assertNotEquals(b.diffHash(), c.diffHash());
        Files.writeString(repo.resolve("a.txt"), "changed\n");
        assertEquals(b.diffHash(), BuildInfo.detect(repo, repo.resolve("eval-results"), "1.0").diffHash());
    }

    @Test
    void aChangeInsideTheResultsFolderIsClean() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("eval-results/kept.json"), "{\"changed\":true}\n");
        BuildInfo b = BuildInfo.detect(repo, repo.resolve("eval-results"), "1.0");
        assertFalse(b.dirty(), b.dirtyFiles().toString());
        assertEquals("none", b.diffHash());
        // the same change counts when that folder is not the results folder
        assertTrue(BuildInfo.detect(repo, repo.resolve("elsewhere"), "1.0").dirty());
    }

    @Test
    void untrackedFilesAreListedButNeverMakeTheTreeDirty() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("new.txt"), "x\n");
        Files.writeString(repo.resolve("eval-results/new-result.json"), "{}\n");
        BuildInfo b = BuildInfo.detect(repo, repo.resolve("eval-results"), "1.0");
        assertFalse(b.dirty());
        assertEquals(List.of("new.txt"), b.untrackedFiles());
        assertEquals(1, b.untrackedFileCount());
    }

    @Test
    void theDirtyFileListIsCapped() throws Exception {
        Path repo = repo();
        for (int i = 0; i < 60; i++) {
            Files.writeString(repo.resolve("f" + i + ".txt"), "x\n");
        }
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "many");
        for (int i = 0; i < 60; i++) {
            Files.writeString(repo.resolve("f" + i + ".txt"), "y\n");
        }
        BuildInfo b = BuildInfo.detect(repo, repo.resolve("eval-results"), "1.0");
        assertEquals(60, b.dirtyFileCount());
        assertEquals(BuildInfo.MAX_FILES, b.dirtyFiles().size());
        assertTrue(DirtyTree.files(b).contains("and 10 more"));
    }

    @Test
    void outsideARepositoryEverythingIsUnknownAndClean() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        Path plain = tmp.resolve("plain");
        Files.createDirectories(plain);
        BuildInfo b = BuildInfo.detect(plain, plain.resolve("eval-results"), "2.0");
        Assumptions.assumeTrue("unknown".equals(b.commit()), "the temp folder lies inside a repository");
        assertFalse(b.dirty());
        assertEquals("unknown", b.branch());
        assertEquals("unknown", b.diffHash());
        assertEquals("2.0", b.projectVersion());
    }

    @Test
    void theUnknownRecordIsCleanAndKeepsTheVersions() {
        BuildInfo b = BuildInfo.unknown("unknown");
        assertEquals("unknown", b.commit());
        assertFalse(b.dirty());
        assertFalse(b.os().isBlank());
    }

    @Test
    void theDirtyTreePolicyRefusesWarnsOrAllowsBeforeAnyModelIsTouched() {
        BuildInfo clean = BuildInfo.unknown("1");
        BuildInfo dirty = new BuildInfo("0123456789abcdef", "main", true, List.of("M a.txt", "M b.txt"), 2, List.of(), 0,
                "h", "d", "1", "21", "os");
        for (DirtyTreePolicy p : DirtyTreePolicy.values()) {
            assertNull(DirtyTree.enforce(p, clean, false));
        }
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DirtyTree.enforce(DirtyTreePolicy.REFUSE, dirty, false));
        assertTrue(refused.getMessage().contains("M a.txt") && refused.getMessage().contains("M b.txt"),
                refused.getMessage());
        assertTrue(DirtyTree.enforce(DirtyTreePolicy.WARN, dirty, false).contains("dirty working tree"));
        assertNull(DirtyTree.enforce(DirtyTreePolicy.ALLOW, dirty, false));
        // a baseline from a dirty tree is refused under every policy
        for (DirtyTreePolicy p : DirtyTreePolicy.values()) {
            assertThrows(IllegalStateException.class, () -> DirtyTree.enforce(p, dirty, true));
        }
        assertNull(DirtyTree.enforce(DirtyTreePolicy.REFUSE, clean, true));
    }

    @Test
    void theDirtyTreePolicyIsParsedCaseInsensitively() {
        assertEquals(DirtyTreePolicy.REFUSE, DirtyTreePolicy.parse(" Refuse "));
        assertEquals("warn", DirtyTreePolicy.WARN.key());
        assertThrows(IllegalArgumentException.class, () -> DirtyTreePolicy.parse("maybe"));
    }
}
