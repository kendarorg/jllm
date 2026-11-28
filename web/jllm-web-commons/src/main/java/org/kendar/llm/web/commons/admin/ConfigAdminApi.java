package org.kendar.llm.web.commons.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.kendar.llm.harness.settings.AgentSettings;
import org.kendar.llm.harness.settings.InstructionRepository;
import org.kendar.llm.harness.settings.VirtualConfigProvider;
import org.kendar.llm.web.commons.Api;
import org.kendar.llm.web.commons.ApiException;
import org.kendar.llm.web.commons.Reply;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Administration of the virtual configuration: the settings document, the instructions (system prompt, global, role
 * and user) and the agents. Only admins get here ({@code /api/admin/**}). Nothing that names a file is accepted: a
 * prompt file, a skill path or instruction files are refused with 400.
 */
public class ConfigAdminApi {

    private static final ObjectMapper MAPPER = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final VirtualConfigProvider provider;

    public ConfigAdminApi(VirtualConfigProvider provider) {
        this.provider = provider;
    }

    // ---- settings document

    public JsonNode settings() {
        return provider.settingsDocument();
    }

    public JsonNode putSettings(JsonNode body) {
        if (body == null || !body.isObject()) throw new ApiException(400, "The settings must be a JSON object");
        try {
            provider.putSettings(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return provider.settingsDocument();
    }

    // ---- instructions

    private static InstructionRepository.Scope scope(String s) {
        try {
            return InstructionRepository.Scope.valueOf(s == null ? "" : s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Scope: system, global, role or user");
        }
    }

    private static Map<String, Object> row(InstructionRepository.Instruction i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", i.scope().name().toLowerCase(Locale.ROOT));
        m.put("owner", i.owner());
        m.put("name", i.name());
        m.put("content", i.content());
        return m;
    }

    /** The instructions of a scope (and owner, for role and user). */
    public List<Map<String, Object>> instructionList(String scope, String owner) {
        var sc = scope(scope);
        try {
            InstructionRepository.checkOwner(sc, owner);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return provider.instructions().list(sc, owner).stream().map(ConfigAdminApi::row).toList();
    }

    public Map<String, Object> instruction(String scope, String owner, String name) {
        var sc = scope(scope);
        return provider.instructions().find(sc, owner, name).map(ConfigAdminApi::row)
                .orElseThrow(() -> new ApiException(404, "Unknown instruction " + scope + "/" + name));
    }

    public Reply<Map<String, Object>> putInstruction(String scope, String owner, String name, Map<String, Object> body) {
        var sc = scope(scope);
        boolean existed = provider.instructions().find(sc, owner, name).isPresent();
        try {
            var written = provider.instructions().write(sc, owner, name, Api.text(body, "content"));
            return Reply.status(existed ? 200 : 201, row(written));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    public void deleteInstruction(String scope, String owner, String name) {
        var sc = scope(scope);
        if (!provider.instructions().delete(sc, owner, name)) {
            throw new ApiException(404, "Unknown instruction " + scope + "/" + name);
        }
    }

    // ---- agents

    private static Map<String, Object> row(AgentSettings a) {
        return MAPPER.convertValue(a, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    public List<Map<String, Object>> agentList() {
        return provider.agents().all().stream().map(ConfigAdminApi::row).toList();
    }

    public Map<String, Object> agent(String name) {
        return provider.agents().find(name).map(ConfigAdminApi::row)
                .orElseThrow(() -> new ApiException(404, "Unknown agent " + name));
    }

    public Reply<Map<String, Object>> putAgent(String name, Map<String, Object> body) {
        Map<String, Object> doc = new LinkedHashMap<>(body == null ? Map.of() : body);
        doc.put("name", name);
        AgentSettings agent;
        try {
            agent = MAPPER.convertValue(doc, AgentSettings.class);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Not an agent: " + e.getMessage());
        }
        boolean existed = provider.agents().find(name).isPresent();
        try {
            return Reply.status(existed ? 200 : 201, row(provider.agents().write(agent)));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    public void deleteAgent(String name) {
        if (!provider.agents().delete(name)) throw new ApiException(404, "Unknown agent " + name);
    }
}
