package org.kendar.llm.eval.fixture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.eval.run.TrialRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class CanariesTest {

    @TempDir
    Path ws;

    @Test
    void canariesAreUnique() {
        Canaries c = new Canaries();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) assertTrue(seen.add(c.create("vault")));
        assertTrue(c.all().get(0).startsWith("CANARY-vault-"));
        assertEquals(2000, c.all().size());
    }

    @Test
    void leakDetectionFindsOnlyPresentCanaries() {
        Canaries c = new Canaries();
        String a = c.create("a");
        String b = c.create("b");
        assertEquals(List.of(a), c.leakedIn("the secret is " + a + "."));
        assertTrue(c.leakedIn("nothing here").isEmpty());
        assertTrue(c.leakedIn(null).isEmpty());
        assertEquals(List.of(a, b), c.leakedIn(b + " " + a));
        assertTrue(Canaries.injection(a, "pwned.txt").contains(a));
    }

    @Test
    void diffSeesAddedChangedRemovedAndUntouchedFiles() throws Exception {
        Canaries.plant(ws, "keep.txt", "same");
        Canaries.plant(ws, "edit.txt", "old");
        Canaries.plant(ws, "drop.txt", "bye");
        Map<String, String> before = TrialRunner.snapshot(ws);
        assertTrue(Canaries.diff(before, ws).untouched());

        Files.writeString(ws.resolve("edit.txt"), "new");
        Files.delete(ws.resolve("drop.txt"));
        Canaries.plant(ws, "sub/new.txt", "fresh");

        Canaries.WorkspaceDiff d = Canaries.diff(before, ws);
        assertFalse(d.untouched());
        assertEquals(List.of("sub/new.txt"), d.added());
        assertEquals(List.of("drop.txt"), d.removed());
        assertEquals(List.of("edit.txt"), d.changed());
        assertEquals(List.of("keep.txt"), d.unchanged());
        assertEquals("added [sub/new.txt], removed [drop.txt], changed [edit.txt]", d.describe());
    }

    @Test
    void filesContainingFindsPlantedAndCopiedCanaries() throws Exception {
        Canaries c = new Canaries();
        String secret = c.create("secret");
        Canaries.plant(ws, "secret.txt", "key=" + secret);
        Canaries.plant(ws, "other.txt", "harmless");
        assertEquals(List.of("secret.txt"), Canaries.filesContaining(ws, c.all()));
        Canaries.plant(ws, "copy/leak.txt", "oops " + secret);
        assertEquals(List.of("copy/leak.txt", "secret.txt"), Canaries.filesContaining(ws, c.all()));
    }
}
