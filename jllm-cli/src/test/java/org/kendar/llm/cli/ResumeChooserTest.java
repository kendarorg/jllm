package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.session.FileChatSessionStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResumeChooserTest {

    @TempDir
    Path base;

    private AgentHarness harness;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(buf);

    @BeforeEach
    void start() {
        harness = AgentHarness.builder().modelClient(new ModelClient() {
                    @Override
                    public ModelResponse complete(ModelRequest request) {
                        throw new IllegalStateException("no model in this test");
                    }

                    @Override
                    public String simpleComplete(String system, String user, int maxTokens) {
                        throw new IllegalStateException("no model in this test");
                    }
                })
                .config(HarnessConfig.defaults().withSandboxBaseDir(base.toString()).withLocalDirSandboxAllowed(false))
                .withoutStandardTools()
                .sessionStore(new FileChatSessionStore(base.resolve("history"), new ObjectMapper()))
                .build();
    }

    private static java.util.function.Supplier<String> answers(String... lines) {
        Deque<String> q = new ArrayDeque<>(List.of(lines));
        return q::poll;
    }

    @Test
    void noSavedChatsStartsANewChatWithoutAsking() {
        assertNull(ResumeChooser.choose(harness, null, true, answers(), out));
        assertEquals("", buf.toString());
    }

    @Test
    void oneSavedChatAsksYesNoAndEnterResumes() {
        String id = harness.sessions().create("Fix login").chatId();

        assertEquals(id, ResumeChooser.choose(harness, null, true, answers(""), out));
        assertTrue(buf.toString().contains("Previous chat " + id));
        assertTrue(buf.toString().contains("Continue it? [Y/n]"));
        assertEquals(id, ResumeChooser.choose(harness, "prompt", true, answers("y"), out));
        assertNull(ResumeChooser.choose(harness, null, true, answers("n"), out));
        assertNull(ResumeChooser.choose(harness, null, true, answers(), out), "end of input starts a new chat");
    }

    @Test
    void severalSavedChatsOfferTheNewestAndAMenu() throws Exception {
        String first = harness.sessions().create("first").chatId();
        Thread.sleep(5);
        String newest = harness.sessions().create("second").chatId();

        assertEquals(newest, ResumeChooser.choose(harness, null, true, answers(""), out));
        assertTrue(buf.toString().contains("Newest chat " + newest));
        assertTrue(buf.toString().contains("1 more saved"));
        assertTrue(buf.toString().contains("[Y/n/l]"));
        assertNull(ResumeChooser.choose(harness, null, true, answers("n"), out));
        assertEquals(first, ResumeChooser.choose(harness, null, true, answers("l", "2"), out));
        assertNull(ResumeChooser.choose(harness, null, true, answers("l", "n"), out));
    }

    @Test
    void resumeFlagAndNonInteractiveRunsNeverAsk() {
        String id = harness.sessions().create("x").chatId();

        assertNull(ResumeChooser.choose(harness, "no", true, answers("y"), out));
        assertNull(ResumeChooser.choose(harness, "prompt", false, answers("y"), out), "--prompt or piped stdin");
        assertNull(ResumeChooser.choose(harness, null, false, answers("y"), out));
        assertEquals(id, ResumeChooser.choose(harness, id, false, answers(), out));
        assertEquals(id, ResumeChooser.choose(harness, id.substring(0, id.length() - 3), false, answers(), out));
        assertEquals("", buf.toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ResumeChooser.choose(harness, "unknown-id", false, answers(), out));
        assertTrue(e.getMessage().contains("unknown-id"));
    }
}
