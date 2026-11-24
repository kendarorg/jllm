package org.kendar.llm.web.sql;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.identity.User;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs only when {@code JLLM_TEST_MYSQL_URL} (a jdbc:mysql url of an empty, disposable database),
 * {@code JLLM_TEST_MYSQL_USER} and {@code JLLM_TEST_MYSQL_PASSWORD} are set; skipped otherwise. Never starts Docker.
 */
class MysqlOptionalTest {

    @Test
    void migratesAndStores() {
        String url = System.getenv("JLLM_TEST_MYSQL_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "no MySQL server configured");
        try (SqlStores s = SqlStores.open(new SqlConfig(url, System.getenv("JLLM_TEST_MYSQL_USER"),
                System.getenv("JLLM_TEST_MYSQL_PASSWORD"), 4, null), null)) {
            String id = "t" + Long.toString(System.nanoTime(), 36);
            User u = s.users().create(id, "N", "admin", null, null);
            assertEquals(id, u.id());
            assertThrows(IllegalStateException.class, () -> s.users().create(id, "N", "admin", null, null));
            assertTrue(s.users().delete(id));
        }
    }
}
