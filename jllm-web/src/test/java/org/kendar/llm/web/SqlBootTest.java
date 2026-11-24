package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.kendar.llm.web.RealHttpApp.Client;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.web.sql.SqlUserRepository;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The whole application on the SQL store (starter on the test class path, {@code jllm.store=sql}): no LLM, no Docker. */
class SqlBootTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private ConfigurableApplicationContext start(String work, String... extra) {
        return RealHttpApp.start(home, work, extra);
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
    void roleInUseIsRefusedWith409NamingTheUsers() throws Exception {
        try (ConfigurableApplicationContext c = start("work5")) {
            Client cl = new Client(c);
            assertEquals(200, cl.login("admin", "admin"));
            assertEquals(201, cl.send("POST", "/api/admin/roles", "{\"name\":\"docs\",\"tools\":[]}").statusCode());
            assertEquals(201, cl.send("POST", "/api/admin/users", "{\"id\":\"dora\",\"role\":\"docs\"}").statusCode());
            HttpResponse<String> refused = cl.send("DELETE", "/api/admin/roles/docs", null);
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("dora"), refused.body());
            assertEquals(400, cl.send("POST", "/api/admin/users", "{\"id\":\"x\",\"role\":\"nosuchrole\"}").statusCode());
            assertEquals(400, cl.send("PUT", "/api/admin/users/dora", "{\"role\":\"nosuchrole\"}").statusCode());
            assertEquals(403, cl.send("DELETE", "/api/admin/roles/admin", null).statusCode());
            assertEquals(204, cl.send("DELETE", "/api/admin/users/dora", null).statusCode());
            assertEquals(204, cl.send("DELETE", "/api/admin/roles/docs", null).statusCode());
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

    @Test
    void defaultPasswordIsEnforcedOverSqlUsers() throws Exception {
        try (ConfigurableApplicationContext c = start("work4", "--jllm.web.enforce-password-change-on-loopback=true")) {
            Client cl = new Client(c);
            assertEquals(200, cl.login("admin", "admin"));
            HttpResponse<String> blocked = cl.send("GET", "/api/me/tools", null);
            assertEquals(403, blocked.statusCode(), blocked.body());
            assertEquals("password_change_required", JSON.readTree(blocked.body()).path("error").asText());
            assertEquals(200, cl.send("GET", "/api/me", null).statusCode(), "the password change stays reachable");
            HttpResponse<String> changed = cl.send("POST", "/api/me/password",
                    "{\"current\":\"admin\",\"next\":\"a-long-new-password\"}");
            assertEquals(204, changed.statusCode(), changed.body());
            assertFalse(c.getBean(UserRepository.class).find("admin").orElseThrow().usesDefaultPassword());
            assertEquals(200, cl.send("GET", "/api/me/tools", null).statusCode(), "unblocked after the change");
        }
    }
}
