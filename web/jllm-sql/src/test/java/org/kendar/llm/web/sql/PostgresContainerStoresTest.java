package org.kendar.llm.web.sql;

import org.junit.jupiter.api.AfterEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The shared store checks on a real PostgreSQL, started with Testcontainers; each test gets a new, empty database.
 * The docker daemon is found as in {@code DockerEndpoints} (jllm-test-support): {@code DOCKER_HOST}, then
 * {@code tcp://localhost:2375}, then {@code /var/run/docker.sock}. Skipped when none answers.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresContainerStoresTest extends AbstractSqlStoresTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private String database;

    @Override
    SqlConfig config() {
        if (database == null) {
            database = "t" + Long.toString(System.nanoTime(), 36);
            try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                 var st = c.createStatement()) {
                st.execute("CREATE DATABASE " + database);
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot create the test database", e);
            }
        }
        return new SqlConfig("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + database, PG.getUsername(), PG.getPassword(), 4, null);
    }

    @AfterEach
    void forgetDatabase() {
        database = null;
    }

    @Override
    boolean indexExists(String name) {
        return !s.database().tx(tx -> tx.query("SELECT indexname FROM pg_indexes WHERE indexname = ?",
                rs -> rs.getString(1), name)).isEmpty();
    }
}
