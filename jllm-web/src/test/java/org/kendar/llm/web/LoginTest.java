package org.kendar.llm.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.web.auth.PasswordHasher;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginTest {

    @TempDir
    Path dir;

    @Test
    void hashesVerifyAndTheDefaultPasswordIsTheSha256OfTheLogin() {
        assertEquals("8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918",
                PasswordHasher.defaultPassword("admin"));
        String h = PasswordHasher.hash("correct horse");
        assertTrue(h.startsWith("pbkdf2$"));
        assertNotEquals(h, PasswordHasher.hash("correct horse"), "salted");
        assertTrue(PasswordHasher.verify("x", "correct horse", h));
        assertFalse(PasswordHasher.verify("x", "wrong", h));
        assertTrue(PasswordHasher.verify("bob", PasswordHasher.defaultPassword("bob"), null));
        assertFalse(PasswordHasher.verify("bob", "bob", null));
        assertFalse(PasswordHasher.verify("bob", "x", "garbage"));
        assertFalse(PasswordHasher.verify("bob", null, null));
    }

    @Test
    void defaultPasswordSignsInAndTheUserListIsGone() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        var me = WebauthFixture.json(f.get(c, "/api/me").andExpect(status().isOk()));
        assertEquals("rita", me.path("id").asText());
        assertTrue(me.path("defaultPassword").asBoolean());
        assertFalse(me.has("password"));
        assertNotEquals(200, f.mvc.perform(get("/api/users")).andReturn().getResponse().getStatus());
    }

    @Test
    void wrongPasswordAndUnknownUserAnswerTheSame() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        String wrong = f.login("rita", "nope").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknown = f.login("ghost", "nope").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertEquals(wrong, unknown);
        f.mvc.perform(post("/api/login").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgedCookiesAreRejected() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        f.mvc.perform(get("/api/chat").cookie(new Cookie("jllm_user", "admin"))).andExpect(status().isUnauthorized());
        f.mvc.perform(get("/api/chat").cookie(new Cookie("jllm_session", "admin"))).andExpect(status().isUnauthorized());
        f.mvc.perform(get("/api/me").cookie(new Cookie("jllm_session", "made-up"))).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheSessionAndDeletedUsersAreSignedOut() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie rita = f.signIn("rita");
        f.mvc.perform(post("/api/logout").cookie(rita)).andExpect(status().isNoContent());
        f.get(rita, "/api/chat").andExpect(status().isUnauthorized());

        Cookie gus = f.signIn("gus");
        f.get(gus, "/api/chat").andExpect(status().isOk());
        f.users.delete("gus");
        f.get(gus, "/api/chat").andExpect(status().isUnauthorized());
    }

    @Test
    void fiveFailuresLockTheLogin() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        for (int i = 0; i < 5; i++) f.login("rita", "nope").andExpect(status().isUnauthorized());
        f.login("rita", PasswordHasher.defaultPassword("rita")).andExpect(status().isTooManyRequests());
    }

    @Test
    void chatsAreVisibleToTheirOwnerAndTheAdminOnly() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie rita = f.signIn("rita");
        Cookie gus = f.signIn("gus");
        Cookie admin = f.signIn("admin");
        f.mvc.perform(get("/api/chat")).andExpect(status().isUnauthorized());

        String chat = WebauthFixture.json(f.send(rita, "POST", "/api/chat", Map.of()).andExpect(status().isOk()))
                .path("chatId").asText();
        f.get(rita, "/api/chat/" + chat).andExpect(status().isOk());
        f.get(gus, "/api/chat/" + chat).andExpect(status().isNotFound());
        f.get(admin, "/api/chat/" + chat).andExpect(status().isOk());
        assertEquals("[]", f.get(gus, "/api/chat").andReturn().getResponse().getContentAsString());
        assertTrue(f.get(rita, "/api/chat").andReturn().getResponse().getContentAsString().contains(chat));
    }
}
