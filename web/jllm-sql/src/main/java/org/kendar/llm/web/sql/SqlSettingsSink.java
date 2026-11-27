package org.kendar.llm.web.sql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.settings.AbstractSettingsSink;
import org.kendar.llm.harness.settings.SettingsSink;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SettingsSink} on {@code settings_section(name, doc)}: one row per section, each write one statement in its own
 * transaction, so writers of different sections (on any node) never overwrite each other. Another node's write is seen
 * here after {@link #reload()}.
 */
public final class SqlSettingsSink extends AbstractSettingsSink {

    private final Database db;

    public SqlSettingsSink(Database db) {
        this.db = db;
    }

    @Override
    public String origin() {
        return "sql:settings_section";
    }

    @Override
    protected Map<String, JsonNode> read() {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        db.run(tx -> tx.query("SELECT name, doc FROM settings_section ORDER BY name", rs -> {
            String name = rs.getString(1);
            try {
                out.put(name, MAPPER.readTree(rs.getString(2)));
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException("Settings section " + name + " is not valid JSON", e);
            }
            return name;
        }));
        return out;
    }

    @Override
    protected boolean write(String section, JsonNode value) {
        if (value == null) {
            return db.tx(tx -> tx.update("DELETE FROM settings_section WHERE name = ?", section) > 0);
        }
        String doc = serialize(value);
        return db.tx(tx -> {
            boolean was = tx.exists("SELECT 1 FROM settings_section WHERE name = ?", section);
            tx.upsert("settings_section", List.of("name"), List.of("doc"), section, doc);
            return was;
        });
    }
}
