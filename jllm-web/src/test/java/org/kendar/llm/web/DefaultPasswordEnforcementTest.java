package org.kendar.llm.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DefaultPasswordEnforcementTest {

    @TempDir
    Path dir;

    private static ResultActions call(MockMvc mvc, Cookie c, String path, String remote) throws Exception {
        return mvc.perform(get(path).cookie(c).with(r -> {
            r.setRemoteAddr(remote);
            return r;
        }));
    }

    private static void assertRequired(ResultActions r) throws Exception {
        r.andExpect(status().isForbidden());
        assertEquals("password_change_required", WebauthFixture.json(r).path("error").asText());
    }

    @Test
    void remoteDefaultPasswordUserIsBlockedExceptPasswordChangeAndAuth() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        assertRequired(call(f.mvc, c, "/api/me/tools", "203.0.113.5"));
        assertRequired(call(f.mvc, c, "/api/me/skills", "203.0.113.5"));
        call(f.mvc, c, "/api/me", "203.0.113.5").andExpect(status().isOk());
        // no controller in the fixture: the interceptor lets it through, so 404 rather than 403
        call(f.mvc, c, "/api/auth/config", "203.0.113.5").andExpect(status().isNotFound());
        f.mvc.perform(post("/api/me/password").cookie(c).contentType("application/json")
                        .content("{\"current\":\"wrong\",\"next\":\"a-long-new-password\"}")
                        .with(r -> {
                            r.setRemoteAddr("203.0.113.5");
                            return r;
                        }))
                .andExpect(status().isUnauthorized());
        f.mvc.perform(post("/api/logout").cookie(c).with(r -> {
            r.setRemoteAddr("203.0.113.5");
            return r;
        })).andExpect(status().isNoContent());
    }

    @Test
    void loopbackIsExemptUnlessEnforced() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        call(f.mvc, c, "/api/me/tools", "127.0.0.1").andExpect(status().isOk());
        call(f.mvc, c, "/api/me/tools", "0:0:0:0:0:0:0:1").andExpect(status().isOk());
        MockMvc strict = f.mvc(true, true);
        assertRequired(call(strict, c, "/api/me/tools", "127.0.0.1"));
    }

    @Test
    void loopbackIsNotExemptWhenTheServerIsNotBoundToLoopback() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        assertRequired(call(f.mvc(false, false), c, "/api/me/tools", "127.0.0.1"));
        assertRequired(call(f.mvc(false, false), c, "/api/me/tools", "0:0:0:0:0:0:0:1"));
        call(f.mvc(false, true), c, "/api/me/tools", "127.0.0.1").andExpect(status().isOk());
    }

    @Test
    void serverAddressClassification() {
        for (String a : new String[]{"127.0.0.1", "127.1.2.3", "::1", "[::1]", "localhost", " 127.0.0.1 "}) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    org.kendar.llm.web.spring.ApiInterceptor.isLoopbackAddress(a), a);
        }
        for (String a : new String[]{null, "", " ", "0.0.0.0", "::", "0:0:0:0:0:0:0:0", "192.168.1.5", "example.com",
                "not an address:x"}) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    org.kendar.llm.web.spring.ApiInterceptor.isLoopbackAddress(a), String.valueOf(a));
        }
    }

    @Test
    void contextPathDoesNotSkipTheAuthentication() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        f.mvc.perform(get("/jllm/api/chat").contextPath("/jllm")).andExpect(status().isUnauthorized());
        f.mvc.perform(get("/jllm/api/chat/commands").contextPath("/jllm")).andExpect(status().isOk());
        f.mvc.perform(post("/jllm/api/login").contextPath("/jllm").contentType("application/json")
                .content("{\"id\":\"rita\",\"password\":\"nope\"}")).andExpect(status().isUnauthorized());
        Cookie c = f.signIn("rita");
        f.mvc.perform(get("/jllm/api/chat").contextPath("/jllm").cookie(c)).andExpect(status().isOk());
        assertRequired(f.mvc.perform(get("/jllm/api/me/tools").contextPath("/jllm").cookie(c).with(r -> {
            r.setRemoteAddr("203.0.113.5");
            return r;
        })));
        f.mvc.perform(get("/jllm/api/me").contextPath("/jllm").cookie(c).with(r -> {
            r.setRemoteAddr("203.0.113.5");
            return r;
        })).andExpect(status().isOk());
    }

    @Test
    void forwardedLoopbackIsNotExempt() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        assertRequired(f.mvc.perform(get("/api/me/tools").cookie(c).header("X-Forwarded-For", "203.0.113.5")));
    }

    @Test
    void afterTheChangeEverythingIsOpen() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        assertRequired(call(f.mvc(true, true), c, "/api/me/tools", "203.0.113.5"));
        f.send(c, "POST", "/api/me/password", Map.of("current", "rita", "next", "a-long-new-password"))
                .andExpect(status().isNoContent());
        Cookie after = f.signIn("rita", "a-long-new-password");
        call(f.mvc(true, true), after, "/api/me/tools", "203.0.113.5").andExpect(status().isOk());
    }

    @Test
    void oneTimePasswordBlocksEveryActionIncludingWritesStreamsAndAdminCalls() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        MockMvc strict = f.mvc(true, true);
        String temp = WebauthFixture.json(f.send(f.signIn("admin"), "POST", "/api/admin/users",
                Map.of("id", "tess", "role", "admin")).andExpect(status().isCreated())).path("temporaryPassword").asText();
        Cookie c = f.signIn("tess", temp);
        String json = "application/json";
        for (var req : new org.springframework.test.web.servlet.RequestBuilder[]{
                get("/api/chat").cookie(c),
                post("/api/chat").cookie(c).contentType(json).content("{}"),
                get("/api/chat/any/events").cookie(c),
                post("/api/chat/any/messages").cookie(c).contentType(json).content("{}"),
                post("/api/chat/any/approvals/a1").cookie(c).contentType(json).content("{}"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/chat/any").cookie(c),
                get("/api/admin/users").cookie(c),
                post("/api/admin/users").cookie(c).contentType(json).content("{\"id\":\"z\",\"role\":\"reader\"}"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/me/tools").cookie(c)
                        .contentType(json).content("{}")}) {
            assertRequired(strict.perform(req));
        }
        // the three allowed: session info, password change, sign-out
        strict.perform(get("/api/me").cookie(c)).andExpect(status().isOk());
        strict.perform(post("/api/me/password").cookie(c).contentType(json)
                .content("{\"current\":\"wrong\",\"next\":\"a-long-new-password\"}")).andExpect(status().isUnauthorized());
        strict.perform(post("/api/logout").cookie(c)).andExpect(status().isNoContent());
    }

    @Test
    void usersWithAPasswordAreNeverHeldBack() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie c = f.signIn("rita");
        f.send(c, "POST", "/api/me/password", Map.of("current", "rita", "next", "a-long-new-password"));
        Cookie after = f.signIn("rita", "a-long-new-password");
        call(f.mvc(true, true), after, "/api/me/tools", "127.0.0.1").andExpect(status().isOk());
    }
}
