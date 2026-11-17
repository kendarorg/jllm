package org.kendar.llm.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.eval.TestSupport.*;

@Tag("unit")
class LocalServerTest {

    /** In-memory container backend recording what was done. */
    static final class FakeBackend implements LocalServer.Backend {
        LocalServer.Existing existing;
        final List<String> log = new ArrayList<>();

        public Optional<LocalServer.Existing> find() {
            return Optional.ofNullable(existing);
        }

        public void remove(String id) {
            log.add("remove");
            existing = null;
        }

        public void create(LocalSettings s, String fp) {
            log.add("create:" + s.model().key());
            existing = new LocalServer.Existing("c1", true, fp);
        }
    }

    private static LocalSettings settings(String model, String... extra) {
        String[] kv = new String[extra.length + 4];
        kv[0] = "eval.target";
        kv[1] = "local." + model;
        kv[2] = "eval.judge";
        kv[3] = "openai";
        System.arraycopy(extra, 0, kv, 4, extra.length);
        return load(map(kv), map()).local();
    }

    private static LocalServer server(LocalSettings s, FakeBackend b) {
        return new LocalServer(s, b, url -> true);
    }

    @Test
    void firstRunStartsOnce() {
        FakeBackend b = new FakeBackend();
        assertEquals(LocalServer.Outcome.STARTED, server(settings("strong"), b).ensureRunning());
        assertEquals(List.of("create:strong"), b.log);
    }

    @Test
    void secondRunOnSameModelReusesWithoutReload() {
        FakeBackend b = new FakeBackend();
        server(settings("strong"), b).ensureRunning();
        assertEquals(LocalServer.Outcome.REUSED, server(settings("strong"), b).ensureRunning());
        assertEquals(List.of("create:strong"), b.log);
    }

    @Test
    void differentModelRestartsExactlyOnce() {
        FakeBackend b = new FakeBackend();
        server(settings("strong"), b).ensureRunning();
        assertEquals(LocalServer.Outcome.RESTARTED, server(settings("alt"), b).ensureRunning());
        assertEquals(List.of("create:strong", "remove", "create:alt"), b.log);
    }

    @Test
    void differentContextSizeRestarts() {
        FakeBackend b = new FakeBackend();
        server(settings("strong"), b).ensureRunning();
        assertEquals(LocalServer.Outcome.RESTARTED,
                server(settings("strong", "eval.local.model.strong.contextTokens", "32768"), b).ensureRunning());
    }

    @Test
    void stoppedContainerIsReplaced() {
        FakeBackend b = new FakeBackend();
        LocalSettings s = settings("strong");
        b.existing = new LocalServer.Existing("old", false, s.fingerprint());
        assertEquals(LocalServer.Outcome.RESTARTED, server(s, b).ensureRunning());
    }

    @Test
    void finishKeepsOrRemovesAccordingToProperty() {
        FakeBackend keep = new FakeBackend();
        LocalServer a = server(settings("strong"), keep);
        a.ensureRunning();
        a.finish();
        assertNotNull(keep.existing);

        FakeBackend drop = new FakeBackend();
        LocalServer d = server(settings("strong", "eval.local.keepRunning", "false"), drop);
        d.ensureRunning();
        d.finish();
        assertNull(drop.existing);
    }

    @Test
    void notReadyServerFailsWithClearMessage() {
        LocalSettings s = settings("strong", "eval.local.startupTimeoutSeconds", "1");
        LocalServer srv = new LocalServer(s, new FakeBackend(), url -> false);
        IllegalStateException e = assertThrows(IllegalStateException.class, srv::ensureRunning);
        assertTrue(e.getMessage().contains("qwen3.6-27b"));
    }
}
