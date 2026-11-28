package org.kendar.llm.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole application over the starter: it boots, serves the page, gates the API and mounts the sign-in. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JllmWebBootTest {

    private static final Path HOME;

    static {
        try {
            HOME = Files.createTempDirectory("jllm-boot");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("jllm.provider", () -> "ollama");
        r.add("jllm.chats-dir", () -> HOME.resolve("work").toString());
        r.add("jllm.webauth-dir", () -> HOME.resolve("webauth").toString());
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    ApplicationContext context;

    @Test
    void servesThePage() {
        ResponseEntity<String> r = http.getForEntity("/", String.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().contains("<"), "an HTML page");
    }

    @Test
    void gatesTheApiButNotTheSignInEndpoints() {
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/api/chat", String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/api/me", String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/api/admin/users", String.class).getStatusCode());
        ResponseEntity<String> config = http.getForEntity("/api/auth/config", String.class);
        assertEquals(HttpStatus.OK, config.getStatusCode());
        assertEquals("{\"password\":true}", config.getBody());
        assertEquals(HttpStatus.OK, http.getForEntity("/api/chat/commands", String.class).getStatusCode());
    }

    @Test
    void theErrorBodyHasTheShapeOfTheApi() {
        assertEquals("{\"error\":\"Sign in first\"}", http.getForEntity("/api/chat", String.class).getBody());
    }

    @Test
    void theStarterClassesAreRegisteredOnceByItsAutoConfiguration() {
        for (Class<?> c : new Class<?>[]{org.kendar.llm.web.spring.ChatController.class,
                org.kendar.llm.web.spring.AuthController.class, org.kendar.llm.web.spring.AdminController.class,
                org.kendar.llm.web.spring.MeController.class, org.kendar.llm.web.spring.OidcController.class}) {
            assertEquals(1, context.getBeansOfType(c).size(), c.getSimpleName());
        }
    }
}
