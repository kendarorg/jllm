package org.kendar.llm.web.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.plugin.PluginStorage;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The plugin storage the workflows use (flows per user and global, runs per user) over SQLite. */
class SqlPluginStorageTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;

    private PluginStorage storage(SqlStores s) {
        return s.pluginStorages().forPlugin("jllm-workflows");
    }

    @Test
    void flowsCrudPerOwnerAndGlobal() {
        try (SqlStores s = SqlStores.open(SqlConfig.sqliteFile(tmp.resolve("t.db")), null)) {
            PluginStorage st = storage(s);
            st.put(PluginStorage.GLOBAL, "flows", "b", M.createObjectNode().put("name", "b"));
            st.put(PluginStorage.GLOBAL, "flows", "a", M.createObjectNode().put("name", "a"));
            st.put("alice", "flows", "mine", M.createObjectNode().put("name", "mine"));
            assertEquals(List.of("a", "b"), st.ids(PluginStorage.GLOBAL, "flows"));
            assertEquals(List.of("mine"), st.ids("alice", "flows"));
            assertTrue(st.ids("bob", "flows").isEmpty());
            assertTrue(st.get("bob", "flows", "mine").isEmpty());
            st.put("alice", "flows", "mine", M.createObjectNode().put("name", "mine").put("v", 2));
            assertEquals(2, st.get("alice", "flows", "mine").orElseThrow().path("v").asInt());
            assertEquals(List.of("_global", "alice"), st.owners("flows"));
            st.delete("alice", "flows", "mine");
            assertTrue(st.get("alice", "flows", "mine").isEmpty());
            assertEquals(List.of("_global"), st.owners("flows"));
        }
    }

    @Test
    void runsAreKeptPerUserAndPluginsAreSeparate() {
        try (SqlStores s = SqlStores.open(SqlConfig.sqliteFile(tmp.resolve("t.db")), null)) {
            PluginStorage st = storage(s);
            for (String id : List.of("r3", "r1", "r2")) st.put("alice", "runs", id, M.createObjectNode().put("runId", id));
            st.put("bob", "runs", "r9", M.createObjectNode().put("runId", "r9"));
            assertEquals(List.of("r1", "r2", "r3"), st.ids("alice", "runs"));
            assertEquals(List.of("alice", "bob"), st.owners("runs"));
            assertTrue(s.pluginStorages().forPlugin("other").ids("alice", "runs").isEmpty());
        }
    }

    @Test
    void documentsSurviveReopen() {
        Path db = tmp.resolve("t.db");
        try (SqlStores s = SqlStores.open(SqlConfig.sqliteFile(db), null)) {
            storage(s).put("alice", "runs", "r1", M.createObjectNode().put("runId", "r1"));
        }
        try (SqlStores s = SqlStores.open(SqlConfig.sqliteFile(db), null)) {
            assertEquals("r1", storage(s).get("alice", "runs", "r1").orElseThrow().path("runId").asText());
        }
    }
}
