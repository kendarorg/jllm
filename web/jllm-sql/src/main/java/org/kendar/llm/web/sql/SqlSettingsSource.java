package org.kendar.llm.web.sql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.settings.SettingsLayer;
import org.kendar.llm.harness.settings.SettingsSource;

import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;

/**
 * {@link SettingsSource} on {@code settings_layer(kind, origin, doc)}: one JSON document per layer kind. The sections an
 * administrator edits at runtime are not here but in {@link SqlSettingsSink}.
 */
public final class SqlSettingsSource implements SettingsSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Database db;

    public SqlSettingsSource(Database db) {
        this.db = db;
    }

    @Override
    public List<SettingsLayer> layers() {
        List<SettingsLayer> out = db.tx(tx -> tx.query("SELECT kind, origin, doc FROM settings_layer", rs -> {
            String kind = rs.getString(1);
            try {
                return new SettingsLayer(SettingsLayer.Kind.valueOf(kind), rs.getString(2), MAPPER.readTree(rs.getString(3)));
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException("Settings layer " + kind + " is not valid JSON", e);
            }
        }));
        return out.stream().sorted(Comparator.comparing(SettingsLayer::kind)).toList();
    }

    /**
     * Stores (replaces) the layer. @param origin for messages, e.g. {@code sql:global}
     *
     * @throws IllegalArgumentException for {@link SettingsLayer.Kind#STORED}: those sections are kept by
     *                                  {@link SqlSettingsSink}
     */
    public void put(SettingsLayer.Kind kind, String origin, JsonNode tree) {
        if (kind == SettingsLayer.Kind.STORED) {
            throw new IllegalArgumentException("The stored settings are kept by SqlSettingsSink, not as a layer");
        }
        String doc;
        try {
            doc = MAPPER.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not serializable settings", e);
        }
        db.run(tx -> tx.upsert("settings_layer", List.of("kind"), List.of("origin", "doc"), kind.name(),
                origin == null ? "sql:" + kind.name().toLowerCase(java.util.Locale.ROOT) : origin, doc));
    }

    /** @return whether the layer existed */
    public boolean remove(SettingsLayer.Kind kind) {
        return db.tx(tx -> tx.update("DELETE FROM settings_layer WHERE kind = ?", kind.name()) > 0);
    }
}
