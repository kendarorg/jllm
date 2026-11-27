package org.kendar.llm.web.sql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.settings.AgentRepository;
import org.kendar.llm.harness.settings.AgentSettings;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/** {@link AgentRepository} on {@code agents(name, doc)}: one JSON document per agent, inline prompt only. */
public final class SqlAgentRepository implements AgentRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Database db;

    public SqlAgentRepository(Database db) {
        this.db = db;
    }

    @Override
    public List<AgentSettings> all() {
        return db.tx(tx -> tx.query("SELECT doc FROM agents ORDER BY name", rs -> parse(rs.getString(1))));
    }

    @Override
    public Optional<AgentSettings> find(String name) {
        if (name == null || !NAME.matcher(name).matches()) return Optional.empty();
        return db.tx(tx -> tx.one("SELECT doc FROM agents WHERE name = ?", rs -> parse(rs.getString(1)), name));
    }

    @Override
    public AgentSettings write(AgentSettings agent) {
        AgentRepository.check(agent);
        String doc;
        try {
            doc = MAPPER.writeValueAsString(agent);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not serializable agent", e);
        }
        db.run(tx -> tx.upsert("agents", List.of("name"), List.of("doc"), agent.name(), doc));
        return agent;
    }

    @Override
    public boolean delete(String name) {
        if (name == null || !NAME.matcher(name).matches()) return false;
        return db.tx(tx -> tx.update("DELETE FROM agents WHERE name = ?", name) > 0);
    }

    private static AgentSettings parse(String doc) {
        try {
            return MAPPER.readValue(doc, AgentSettings.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("An agent is not valid JSON", e);
        }
    }
}
