package org.kendar.llm.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.settings.FileFolderTrust;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FolderTrustGateTest {

    @TempDir
    Path tmp;

    private Path project() throws Exception {
        Path p = Files.createDirectories(tmp.resolve("project/.jllm")).getParent();
        Files.writeString(p.resolve(".jllm/settings.json"), "{}");
        return p;
    }

    private FileFolderTrust trust() {
        return new FileFolderTrust(tmp.resolve("home/trusted.json"));
    }

    @Test
    void decliningIsNeverStoredSoTheNextStartAsksAgain() throws Exception {
        Path p = project();
        FileFolderTrust t = trust();
        List<String> asked = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            assertFalse(FolderTrustGate.allow(p, Map.of(), List.of(), t, true, q -> {
                asked.add(q);
                return false;
            }, warnings::add));
        }
        assertEquals(3, asked.size(), "asked on every start");
        assertEquals(3, warnings.size());
        assertTrue(t.record(p).isEmpty(), "no record of the refusal");
        assertFalse(Files.exists(tmp.resolve("home/trusted.json")), "nothing written at all");
        // no answer (null) is a refusal too
        assertFalse(FolderTrustGate.allow(p, Map.of(), List.of(), t, true, q -> null, warnings::add));
        assertTrue(t.record(p).isEmpty());
    }

    @Test
    void yesTrustsUntilTheJllmContentChanges() throws Exception {
        Path p = project();
        FileFolderTrust t = trust();
        List<String> asked = new ArrayList<>();
        assertTrue(FolderTrustGate.allow(p, Map.of(), List.of(), t, true, q -> {
            asked.add(q);
            return true;
        }, w -> fail(w)));
        assertTrue(FolderTrustGate.allow(p, Map.of(), List.of(), t, true, q -> {
            asked.add(q);
            return true;
        }, w -> fail(w)));
        assertEquals(1, asked.size());
        Files.writeString(p.resolve(".jllm/settings.json"), "{\"changed\":1}");
        assertTrue(FolderTrustGate.allow(p, Map.of(), List.of(), t, true, q -> {
            asked.add(q);
            return true;
        }, w -> fail(w)));
        assertEquals(2, asked.size(), "a changed .jllm asks again");
        assertTrue(asked.get(1).contains("changed"), asked.get(1));
    }

    @Test
    void unattendedUntrustedFolderIsRefusedUnlessFlaggedOrListed() throws Exception {
        Path p = project();
        FileFolderTrust t = trust();
        List<String> warnings = new ArrayList<>();
        assertFalse(FolderTrustGate.allow(p, Map.of(), List.of(), t, false, q -> fail("asked"), warnings::add));
        assertEquals(1, warnings.size());
        assertTrue(FolderTrustGate.allow(p, Map.of("trust-project", ""), List.of(), t, false, q -> fail("asked"),
                w -> fail(w)));
        assertTrue(FolderTrustGate.allow(p, Map.of(), List.of(p.toString()), t, false, q -> fail("asked"),
                w -> fail(w)));
        assertFalse(FolderTrustGate.allow(p, Map.of(), List.of(tmp.toString()), t, false, q -> fail("asked"),
                w -> { }));
        assertTrue(t.record(p).isEmpty(), "the flag and the list store nothing");
    }

    @Test
    void projectSystemPromptNeedsApproval() throws Exception {
        Path p = project();
        Files.writeString(p.resolve(".jllm/SYSTEM.md"), "replace everything");
        FileFolderTrust t = trust();
        t.trust(p, Set.of());
        List<String> warnings = new ArrayList<>();
        assertFalse(FolderTrustGate.projectSystemPrompt(p, t, false, q -> fail("asked"), warnings::add));
        assertEquals(1, warnings.size());
        assertFalse(FolderTrustGate.projectSystemPrompt(p, t, true, q -> false, w -> { }));
        assertTrue(FolderTrustGate.projectSystemPrompt(p, t, true, q -> true, w -> fail(w)));
        // the approval sits in the record, but the changed file is a new hash: asked again
        assertTrue(t.record(p).orElseThrow().loosenings().contains("SYSTEM.md"));
    }
}
