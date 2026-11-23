package org.kendar.llm.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A URI variant never serves another user's chat: it is a 4xx, or the chat id the framework matched is checked. */
class ChatUriVariantsTest {

    @TempDir
    Path dir;

    @Test
    void noUriVariantServesAChatToAnotherUser() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        Cookie rita = f.signIn("rita");
        Cookie gus = f.signIn("gus");
        String chat = WebauthFixture.json(f.send(rita, "POST", "/api/chat", Map.of())).path("chatId").asText();
        List<String> variants = List.of(
                "/api//chat/" + chat, "/api/./chat/" + chat, "/api/chat//" + chat, "/api/chat/./" + chat,
                "/api/chat/" + chat + "/", "/api/chat/" + chat + "//", "/API/chat/" + chat, "/api/CHAT/" + chat,
                "/api/chat/" + chat.toUpperCase(), "/api/chat/%2e/" + chat, "/api/%2e/chat/" + chat,
                "/api/chat/%2e%2e/chat/" + chat, "/api/chat/" + chat + "%2F", "/api/chat%2F" + chat,
                "/api/chat/" + chat + ";jsessionid=x", "/api/chat;x=1/" + chat, "/api/chat/;x/" + chat,
                "/api/chat/" + chat + "/..;/" + chat, "/api/chat/" + chat + "%00", "/api/chat/%63hat/" + chat,
                "/api/chat/" + chat.substring(0, 2) + "%" + Integer.toHexString(chat.charAt(2)) + chat.substring(3),
                "/api/chat/commands/../" + chat, "/api/chat/" + chat + "/messages/../..");
        for (String v : variants) {
            String body = f.mvc.perform(get(URI.create(v)).cookie(gus)).andReturn().getResponse().getContentAsString();
            int status = f.mvc.perform(get(URI.create(v)).cookie(gus)).andReturn().getResponse().getStatus();
            assertTrue(status >= 400, v + " -> " + status);
            assertTrue(!body.contains("\"messages\""), v);
        }
        // and the owner is served by the canonical path
        f.mvc.perform(get("/api/chat/" + chat).cookie(rita)).andExpect(status().isOk());
        f.mvc.perform(get("/api/chat/" + chat).cookie(gus)).andExpect(status().isNotFound());
        assertEquals(404, f.mvc.perform(get("/api/chat/" + chat + "/usage").cookie(gus)).andReturn().getResponse()
                .getStatus());
    }
}
