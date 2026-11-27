package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.web.commons.auth.CookieAuthentication;
import org.kendar.llm.web.commons.auth.LoginThrottle;
import org.kendar.llm.web.commons.auth.PasswordHasher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Passwords, sign-in throttle, CSRF backstop and session lifetime through the whole web API. */
class AuthHardeningWebTest {

    @TempDir
    Path dir;

    private static ResultActions login(MockMvc mvc, String id, String password) throws Exception {
        return mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content(WebauthFixture.MAPPER.writeValueAsString(Map.of("id", id, "password", password))));
    }

    private static Cookie cookie(ResultActions r) throws Exception {
        Cookie c = r.andReturn().getResponse().getCookie(CookieAuthentication.COOKIE);
        assertNotNull(c);
        return c;
    }

    // ---- passwords

    @Test
    void anAdminWithoutAPasswordCreatesAUserWithAOneTimePasswordThatMustBeChanged() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        MockMvc strict = f.mvc(true, true); // enforced for loopback callers too
        Cookie admin = f.signIn("admin");

        JsonNode created = WebauthFixture.json(f.send(admin, "POST", "/api/admin/users",
                Map.of("id", "newbie", "role", "reader")).andExpect(status().isCreated()));
        String temp = created.path("temporaryPassword").asText();
        assertEquals(PasswordHasher.GENERATED_LENGTH, temp.length());
        assertTrue(created.path("defaultPassword").asBoolean(), "flagged as must-change");
        assertFalse(created.has("password"));
        login(strict, "newbie", "newbie").andExpect(status().isUnauthorized()); // not the default password any more

        Cookie c = cookie(login(strict, "newbie", temp).andExpect(status().isOk()));
        strict.perform(get("/api/chat").cookie(c)).andExpect(status().isForbidden());
        strict.perform(post("/api/me/password").cookie(c).contentType(MediaType.APPLICATION_JSON)
                .content(WebauthFixture.MAPPER.writeValueAsString(Map.of("current", temp, "next", "my-own-new-password"))))
                .andExpect(status().isNoContent());

        strict.perform(get("/api/chat").cookie(cookie(login(strict, "newbie", "my-own-new-password"))))
                .andExpect(status().isOk());
        login(strict, "newbie", temp).andExpect(status().isUnauthorized());
        assertFalse(f.users.find("newbie").orElseThrow().mustChangePassword());
    }

    @Test
    void anExplicitPasswordIsCheckedAndNotForcedToChange() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie admin = f.signIn("admin");

        f.send(admin, "POST", "/api/admin/users", Map.of("id", "weak1", "role", "reader", "password", "short"))
                .andExpect(status().isBadRequest());
        f.send(admin, "POST", "/api/admin/users", Map.of("id", "weak1", "role", "reader", "password", "weak1"))
                .andExpect(status().isBadRequest());
        JsonNode ok = WebauthFixture.json(f.send(admin, "POST", "/api/admin/users",
                Map.of("id", "good1", "role", "reader", "password", "a-chosen-password")).andExpect(status().isCreated()));

        assertFalse(ok.has("temporaryPassword"));
        assertFalse(ok.path("defaultPassword").asBoolean());
        login(f.mvc, "good1", "a-chosen-password").andExpect(status().isOk());
        assertFalse(f.users.exists("weak1"), "nothing was created by the refused requests");
    }

    @Test
    void aPasswordResetGeneratesAOneTimePasswordSignsOutAndUnlocks() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie rita = f.signIn("rita");
        Cookie admin = f.signIn("admin");

        JsonNode reset = WebauthFixture.json(f.send(admin, "POST", "/api/admin/users/rita/password", Map.of())
                .andExpect(status().isOk()));
        String temp = reset.path("temporaryPassword").asText();

        assertEquals(PasswordHasher.GENERATED_LENGTH, temp.length());
        f.get(rita, "/api/chat").andExpect(status().isUnauthorized()); // signed out everywhere
        login(f.mvc, "rita", "rita").andExpect(status().isUnauthorized()); // no "reset to the default" any more
        assertTrue(f.users.find("rita").orElseThrow().temporaryPassword());
        login(f.mvc, "rita", temp).andExpect(status().isOk());

        JsonNode set = WebauthFixture.json(f.send(admin, "POST", "/api/admin/users/rita/password",
                Map.of("password", "chosen-by-the-admin")).andExpect(status().isOk()));
        assertFalse(set.has("temporaryPassword"));
        login(f.mvc, "rita", "chosen-by-the-admin").andExpect(status().isOk());
    }

    // ---- login throttle

    @Test
    void threeFailuresLockTheLoginAndAnAdminUnlocksIt() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        for (int i = 0; i < 3; i++) login(f.mvc, "rita", "nope").andExpect(status().isUnauthorized());

        login(f.mvc, "rita", "rita").andExpect(status().isTooManyRequests());
        login(f.mvc, "gus", "gus").andExpect(status().isOk()); // another login is not affected

        Cookie admin = f.signIn("admin");
        JsonNode row = null;
        for (JsonNode u : WebauthFixture.json(f.get(admin, "/api/admin/users"))) if ("rita".equals(u.path("id").asText())) row = u;
        assertNotNull(row);
        assertTrue(row.path("lockedFor").asLong() > 29 * 60, "the list shows the lock: " + row);

        assertTrue(WebauthFixture.json(f.send(admin, "POST", "/api/admin/users/rita/unlock", Map.of()))
                .path("wasLocked").asBoolean());
        login(f.mvc, "rita", "rita").andExpect(status().isOk());
        f.send(admin, "POST", "/api/admin/users/ghost/unlock", Map.of()).andExpect(status().isNotFound());
    }

    @Test
    void theLockLastsThirtyMinutes() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        for (int i = 0; i < 3; i++) login(f.mvc, "rita", "nope");
        login(f.mvc, "rita", "rita").andExpect(status().isTooManyRequests());

        // the throttle runs on the real clock here: its own clock is tested in LoginThrottleTest; this checks the wiring
        assertTrue(f.throttle.lockedFor(LoginThrottle.USER_PREFIX + "rita") > Duration.ofMinutes(29).toSeconds());
    }

    @Test
    void onlyAnAdminCanUnlock() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        for (int i = 0; i < 3; i++) login(f.mvc, "rita", "nope");
        Cookie gus = f.signIn("gus");

        f.send(gus, "POST", "/api/admin/users/rita/unlock", Map.of()).andExpect(status().isForbidden());
        login(f.mvc, "rita", "rita").andExpect(status().isTooManyRequests());
        f.mvc.perform(post("/api/admin/users/rita/unlock")).andExpect(status().isUnauthorized());
    }

    @Test
    void aWrongPasswordForUnknownAndKnownLoginsCountsTheSame() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        for (int i = 0; i < 3; i++) login(f.mvc, "ghost", "nope").andExpect(status().isUnauthorized());
        login(f.mvc, "ghost", "nope").andExpect(status().isTooManyRequests()); // no difference to tell it does not exist
    }

    @Test
    void passwordChangeAttemptsShareTheLoginLock() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie rita = f.signIn("rita");
        for (int i = 0; i < 3; i++) {
            f.send(rita, "POST", "/api/me/password", Map.of("current", "wrong", "next", "a-new-good-password"))
                    .andExpect(status().isUnauthorized());
        }
        f.send(rita, "POST", "/api/me/password", Map.of("current", "rita", "next", "a-new-good-password"))
                .andExpect(status().isTooManyRequests());
    }

    // ---- CSRF backstop

    @Test
    void aStateChangingRequestFromAnotherSiteIsRefused() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie rita = f.signIn("rita");

        f.mvc.perform(post("/api/logout").cookie(rita).header("Host", "jllm.example.com")
                .header("Origin", "https://evil.example.net")).andExpect(status().isForbidden());
        f.mvc.perform(post("/api/logout").cookie(rita).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        f.mvc.perform(post("/api/logout").cookie(rita).header("Origin", "null")).andExpect(status().isForbidden());
        f.get(rita, "/api/chat").andExpect(status().isOk()); // the session survived every refused logout

        f.mvc.perform(post("/api/logout").cookie(rita).header("Host", "jllm.example.com")
                .header("Origin", "https://jllm.example.com").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isNoContent());
    }

    @Test
    void theLoginIsProtectedToo() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);

        f.mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).header("Sec-Fetch-Site", "cross-site")
                .content("{\"id\":\"rita\",\"password\":\"rita\"}")).andExpect(status().isForbidden());
        f.mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"rita\",\"password\":\"rita\"}")).andExpect(status().isOk());
    }

    @Test
    void readsFromAnotherSiteAreNotTheBackstopsBusiness() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie rita = f.signIn("rita");

        f.mvc.perform(get("/api/chat").cookie(rita).header("Origin", "https://evil.example.net")
                .header("Sec-Fetch-Site", "cross-site")).andExpect(status().isOk());
    }

    // ---- session lifetime

    @Test
    void aSessionEndsAtItsAbsoluteLifetimeEvenWhenItIsUsedEveryDay() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        for (int day = 1; day <= 29; day++) {
            f.clock.now = f.clock.now.plus(Duration.ofDays(1)); // used daily: the sliding 7 days never run out
            f.get(c, "/api/me").andExpect(status().isOk());
        }

        f.clock.now = f.clock.now.plus(Duration.ofDays(2));

        f.get(c, "/api/me").andExpect(status().isUnauthorized());
        f.signIn("rita"); // signing in again starts a new session
    }

    @Test
    void anIdleSessionStillEndsAfterSevenDays() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");

        f.clock.now = f.clock.now.plus(Duration.ofDays(8));

        f.get(c, "/api/me").andExpect(status().isUnauthorized());
    }

    @Test
    void theCookieDoesNotOutliveTheAbsoluteLifetime() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);

        Cookie c = f.signIn("rita");

        assertEquals(Duration.ofDays(7).toSeconds(), c.getMaxAge(), "min of the sliding and the absolute lifetime");
    }
}
