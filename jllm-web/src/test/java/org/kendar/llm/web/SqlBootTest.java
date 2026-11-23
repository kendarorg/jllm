package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.web.sql.SqlUserRepository;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The whole application on the SQL store (starter on the test class path, {@code jllm.store=sql}): no LLM, no Docker. */
class SqlBootTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private static String slashes(Path p) {
        return p.toString().replace(File.separatorChar, '/');
    }

    private ConfigurableApplicationContext start(String work, String... extra) {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--jllm.provider=ollama", "--jllm.store=sql",
                "--jllm.workdir=" + slashes(home.resolve(work)),
                "--jllm.sql.url=jdbc:sqlite:" + slashes(home.resolve("jllm.db")),
                "--spring.main.banner-mode=off"));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(JllmWebApplication.class).web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new));
    }

    private static final class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        final String base;

        Client(ConfigurableApplicationContext c) {
            base = "http://localhost:" + c.getEnvironment().getProperty("local.server.port");
        }

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        int login(String id, String password) throws Exception {
            return send("POST", "/api/login", "{\"id\":\"" + id + "\",\"password\":\"" + password + "\"}").statusCode();
        }
    }

    @Test
    void seededAdminCreatesAChatThatSurvivesARestart() throws Exception {
        String chatId;
        try (ConfigurableApplicationContext c = start("work1")) {
            assertInstanceOf(SqlUserRepository.class, c.getBean(UserRepository.class));
            assertEquals(1, c.getBeansOfType(UserRepository.class).size(), "no file user repository next to the SQL one");
            Client cl = new Client(c);
            assertEquals(401, cl.send("GET", "/api/chat", null).statusCode());
            assertEquals(401, cl.login("admin", "wrong"));
            assertEquals(200, cl.login("admin", "admin"));
            HttpResponse<String> created = cl.send("POST", "/api/chat", "{}");
            assertEquals(200, created.statusCode(), created.body());
            chatId = JSON.readTree(created.body()).path("chatId").asText();
            assertFalse(chatId.isBlank());
        }
        assertTrue(Files.isRegularFile(home.resolve("jllm.db")));
        // the history root lock of a workdir is held until the process ends: the second run uses another one
        try (ConfigurableApplicationContext c = start("work2")) {
            Client cl = new Client(c);
            assertEquals(200, cl.login("admin", "admin"));
            JsonNode list = JSON.readTree(cl.send("GET", "/api/chat", null).body());
            boolean found = false;
            for (JsonNode n : list) found |= chatId.equals(n.path("chatId").asText());
            assertTrue(found, "the chat is still there: " + list);
            assertEquals(3, c.getBean(UserRepository.class).all().size(), "seeded once, not again");
        }
    }

    @Test
    void realPasswordsReplaceTheSeededDefaults() throws Exception {
        try (ConfigurableApplicationContext c = start("work3", "--jllm.web.use-real-password=true")) {
            UserRepository users = c.getBean(UserRepository.class);
            for (User u : users.all()) assertFalse(u.usesDefaultPassword(), u.id());
            assertEquals(401, new Client(c).login("admin", "admin"));
        }
    }
}
