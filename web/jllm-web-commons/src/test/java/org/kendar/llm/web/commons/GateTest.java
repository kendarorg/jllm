package org.kendar.llm.web.commons;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.identity.Principal;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateTest {

    private static final Fixtures.User ALICE = new Fixtures.User("alice", false);
    private static final Fixtures.User BOB = new Fixtures.User("bob", false);
    private static final Fixtures.User ADMIN = new Fixtures.User("admin", true);

    @TempDir
    Path dir;
    private Gate gate;
    private String aliceChat;

    @BeforeEach
    void setUp() {
        AgentHarness harness = Fixtures.harness(dir);
        aliceChat = (String) Fixtures.chatApi(harness).create(Map.of(), ALICE).get("chatId");
        WebAuthentication auth = new WebAuthentication() {
            @Override
            public Optional<Principal> current(Cookies cookies) {
                return Optional.empty();
            }

            @Override
            public Set<String> openPaths() {
                return Set.of("/api/login", "/api/me");
            }
        };
        gate = new Gate(auth, Fixtures.OWNER_OR_ADMIN, harness.sessions());
    }

    private void expect(int status, String message, Gate.Decision d) {
        assertEquals(status, d.status());
        assertEquals(message, d.message());
        assertEquals(status == 0, d.allowed());
    }

    @Test
    void whatIsNotTheApiIsOpen() {
        assertTrue(gate.isOpen("/"));
        assertTrue(gate.isOpen("/index.html"));
        assertTrue(gate.isOpen("/oauth/callback"));
        assertTrue(gate.decide("/static/app.js", null).allowed());
    }

    @Test
    void theOpenPathsNeedNobody() {
        for (String p : new String[]{"/api/chat/commands", "/api/login", "/api/me"}) {
            assertTrue(gate.isOpen(p), p);
            assertTrue(gate.decide(p, null).allowed(), p);
        }
        assertFalse(gate.isOpen("/api/chat"));
        assertFalse(gate.isOpen("/api/me/tools"), "only the exact path is open");
        assertFalse(gate.isOpen("/api/login/x"));
    }

    @Test
    void everythingElseNeedsASignedInUser() {
        expect(401, "Sign in first", gate.decide("/api/chat", null));
        expect(401, "Sign in first", gate.decide("/api/oauth", null));
        expect(0, null, gate.decide("/api/chat", ALICE));
    }

    @Test
    void aChatTheUserCannotSeeIs404NotForbidden() {
        expect(0, null, gate.decideChat(ALICE, aliceChat));
        expect(0, null, gate.decideChat(ADMIN, aliceChat));
        expect(404, "Unknown chat " + aliceChat, gate.decideChat(BOB, aliceChat));
        expect(404, "Unknown chat nope", gate.decideChat(ALICE, "nope"));
    }

    @Test
    void theUnauthenticatedAnswerComesBeforeTheChatCheck() {
        expect(401, "Sign in first", gate.decide("/api/chat/nope", null));
        expect(401, "Sign in first", gate.decideChat(null, "nope"));
    }

    @Test
    void failsClosedOnPathsItCannotClassify() {
        for (String p : new String[]{"api/chat", "/api", "/API/chat", "/Api/me", "/api//chat", "/api/./chat",
                "/api/chat/../me", "/api;x/chat", "/api%2Fchat", "/x/../api/chat", "", "/a\b",
                "/x//y", "/api/login/", "/api/ME"}) {
            assertFalse(gate.isOpen(p), p);
            expect(401, "Sign in first", gate.decide(p, null));
        }
    }

    @Test
    void theChatCommandsPathIsNotAChat() {
        expect(0, null, gate.decide("/api/chat/commands", null));
        expect(0, null, gate.decide("/api/chat/commands", BOB));
    }

    @Test
    void adminNeedsAnAdmin() {
        expect(401, "Sign in first", gate.decideAdmin(null));
        expect(403, "Admins only", gate.decideAdmin(ALICE));
        expect(0, null, gate.decideAdmin(ADMIN));
        assertNull(gate.decideAdmin(ADMIN).message());
    }
}
