package org.kendar.llm.web.sql.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.web.sql.SqlConfig;
import org.kendar.llm.web.sql.SqlStores;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class SqlSeederTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path TEMPLATE = Path.of("../../release/template/configs/unsafe/webauth");

    private static SqlStores open(Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("seed.db").toString().replace(File.separatorChar, '/');
        return SqlStores.open(SqlConfig.fromProperties(k -> k.equals("jllm.sql.url") ? url : null, dir), null);
    }

    /** The seeded roles must equal release/template/configs/unsafe/webauth/permissions: a drift in either place fails here. */
    @Test
    void seededRolesEqualTheReleaseTemplate(@TempDir Path dir) throws Exception {
        assertTrue(Files.isDirectory(TEMPLATE), "template not found from " + Path.of("").toAbsolutePath());
        try (SqlStores s = open(dir)) {
            assertTrue(SqlSeeder.seed(s.users(), s.roles()));
            for (String name : List.of("admin", "poweru", "guest")) {
                JsonNode t = JSON.readTree(TEMPLATE.resolve("permissions").resolve(name + ".json").toFile());
                Role r = s.roles().find(name).orElseThrow();
                List<String> tools = new ArrayList<>();
                t.path("tools").forEach(n -> tools.add(n.asText()));
                assertEquals(tools, r.tools(), name + " tools");
                Map<String, String> perms = new java.util.LinkedHashMap<>();
                t.path("toolPermissions").fields().forEachRemaining(e -> perms.put(e.getKey(), e.getValue().asText()));
                Map<String, String> actual = new java.util.LinkedHashMap<>();
                r.toolPermissions().forEach((k, v) -> actual.put(k, v.name().toLowerCase(java.util.Locale.ROOT)));
                assertEquals(perms, actual, name + " toolPermissions");
                assertEquals(t.path("readOnly").asBoolean(false), r.readOnly(), name + " readOnly");
                assertEquals(t.path("approval").asText("ask"), r.approval(), name + " approval");
                JsonNode u = JSON.readTree(TEMPLATE.resolve("users").resolve(name + ".json").toFile());
                assertEquals(u.path("role").asText(), s.users().find(name).orElseThrow().role(), name + " user role");
            }
            assertEquals(Map.of("bash", "deny"), Map.of("bash",
                    s.roles().find("guest").orElseThrow().toolPermissions().get("bash").name().toLowerCase(java.util.Locale.ROOT)));
            assertFalse(SqlSeeder.seed(s.users(), s.roles()), "a seeded database is left alone");
        }
    }

    /** Several instances starting on one database: nobody fails, there are exactly 3 users and 3 roles. */
    @Test
    void concurrentSeedingNeverFails(@TempDir Path dir) throws Exception {
        int n = 4;
        List<SqlStores> stores = new ArrayList<>();
        for (int i = 0; i < n; i++) stores.add(open(dir));
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> res = new ArrayList<>();
            for (SqlStores s : stores) {
                res.add(pool.submit(() -> {
                    go.await();
                    return SqlSeeder.seed(s.users(), s.roles());
                }));
            }
            go.countDown();
            int winners = 0;
            for (Future<Boolean> f : res) if (f.get()) winners++;
            assertTrue(winners >= 1, "somebody seeded");
            assertEquals(3, stores.get(0).users().all().size());
            assertEquals(3, stores.get(0).roles().all().size());
        } finally {
            pool.shutdownNow();
            for (SqlStores s : stores) s.close();
        }
    }
}
