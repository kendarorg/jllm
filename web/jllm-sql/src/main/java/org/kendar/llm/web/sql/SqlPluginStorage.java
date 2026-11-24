package org.kendar.llm.web.sql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.plugin.PluginStorage;
import org.kendar.llm.harness.plugin.PluginStorages;

import java.util.List;
import java.util.Optional;

/**
 * {@link PluginStorages} on {@code plugin_docs(plugin_id, owner, collection, doc_id, doc)}: every plugin's documents
 * (the workflows' flows and runs included) live in the database, apart by plugin id.
 */
public final class SqlPluginStorage implements PluginStorages {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Database db;

    public SqlPluginStorage(Database db) {
        this.db = db;
    }

    @Override
    public PluginStorage forPlugin(String pluginId) {
        return new Area(pluginId);
    }

    private final class Area implements PluginStorage {
        private final String plugin;

        Area(String plugin) {
            this.plugin = plugin;
        }

        @Override
        public Optional<JsonNode> get(String owner, String collection, String id) {
            return db.tx(tx -> tx.one("SELECT doc FROM plugin_docs WHERE plugin_id = ? AND owner = ? AND collection = ?"
                    + " AND doc_id = ?", rs -> rs.getString(1), plugin, owner, collection, id)).map(Area::parse);
        }

        @Override
        public void put(String owner, String collection, String id, JsonNode doc) {
            String json;
            try {
                json = MAPPER.writeValueAsString(doc);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Not serializable document " + id, e);
            }
            db.run(tx -> tx.upsert("plugin_docs", List.of("plugin_id", "owner", "collection", "doc_id"),
                    List.of("doc"), plugin, owner, collection, id, json));
        }

        @Override
        public void delete(String owner, String collection, String id) {
            db.run(tx -> tx.update("DELETE FROM plugin_docs WHERE plugin_id = ? AND owner = ? AND collection = ?"
                    + " AND doc_id = ?", plugin, owner, collection, id));
        }

        @Override
        public List<String> ids(String owner, String collection) {
            // sorted here, not by the database: the collation differs between the databases
            return db.tx(tx -> tx.query("SELECT doc_id FROM plugin_docs WHERE plugin_id = ? AND owner = ?"
                    + " AND collection = ?", rs -> rs.getString(1), plugin, owner, collection)).stream().sorted().toList();
        }

        @Override
        public List<String> owners(String collection) {
            return db.tx(tx -> tx.query("SELECT DISTINCT owner FROM plugin_docs WHERE plugin_id = ? AND collection = ?",
                    rs -> rs.getString(1), plugin, collection)).stream().sorted().toList();
        }

        private static JsonNode parse(String json) {
            try {
                return MAPPER.readTree(json);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Unreadable plugin document: " + e.getMessage(), e);
            }
        }
    }
}
