package org.kendar.llm.web.sql;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.settings.ProjectMcpTrust;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/** {@link ProjectMcpTrust} on {@code mcp_trust(project_key, name, sha256, trusted)}. */
public final class SqlProjectMcpTrust implements ProjectMcpTrust {

    private static final Logger log = LoggerFactory.getLogger(SqlProjectMcpTrust.class);

    private final Database db;

    public SqlProjectMcpTrust(Database db) {
        this.db = db;
    }

    @Override
    public Boolean lookup(Path projectDir, String server, JsonNode definition) {
        String key = ProjectMcpTrust.projectKey(projectDir);
        String fp = ProjectMcpTrust.fingerprint(definition);
        return db.tx(tx -> tx.one("SELECT trusted FROM mcp_trust WHERE project_key = ? AND name = ? AND sha256 = ?",
                rs -> rs.getInt(1) != 0, key, server, fp)).orElse(null);
    }

    @Override
    public void remember(Path projectDir, String server, JsonNode definition, boolean trusted) {
        try {
            String key = ProjectMcpTrust.projectKey(projectDir);
            String fp = ProjectMcpTrust.fingerprint(definition);
            db.run(tx -> tx.upsert("mcp_trust", List.of("project_key", "name"), List.of("sha256", "trusted"), key,
                    server, fp, trusted));
        } catch (RuntimeException e) { // boundary: a failure is asked again, as the file one
            log.warn("Cannot remember the MCP trust answer for {}: {}", server, e.toString());
        }
    }
}
