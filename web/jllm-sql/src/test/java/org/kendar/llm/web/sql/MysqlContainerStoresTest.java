package org.kendar.llm.web.sql;

import org.junit.jupiter.api.AfterEach;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The shared store checks on a real MySQL, started with Testcontainers; each test gets a new, empty database.
 * The docker daemon is found as in {@code DockerEndpoints} (jllm-test-support): {@code DOCKER_HOST}, then
 * {@code tcp://localhost:2375}, then {@code /var/run/docker.sock}. Skipped when none answers.
 */
@Testcontainers(disabledWithoutDocker = true)
class MysqlContainerStoresTest extends AbstractSqlStoresTest {

    private static final String ROOT = "root";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private String database;

    @Override
    SqlConfig config() {
        if (database == null) {
            database = "t" + Long.toString(System.nanoTime(), 36);
            // the container's root has the same password as its test user, and may create databases
            try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), ROOT, MYSQL.getPassword());
                 var st = c.createStatement()) {
                st.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot create the test database", e);
            }
        }
        return SqlConfig.mysql(MYSQL.getHost(), MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT), database,
                ROOT, MYSQL.getPassword());
    }

    @AfterEach
    void forgetDatabase() {
        database = null;
    }

    @Override
    boolean indexExists(String name) {
        return !s.database().tx(tx -> tx.query(
                "SELECT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND index_name = ?",
                rs -> rs.getString(1), name)).isEmpty();
    }
}
