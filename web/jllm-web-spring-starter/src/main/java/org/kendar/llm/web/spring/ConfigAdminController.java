package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.web.commons.admin.ConfigAdminApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * {@code /api/admin/config/**}: the settings document, the instructions and the agents of the virtual configuration.
 * The admin gate has already run.
 */
@RestController
@RequestMapping("/api/admin/config")
public class ConfigAdminController {

    private final ConfigAdminApi config;

    public ConfigAdminController(ConfigAdminApi config) {
        this.config = config;
    }

    @GetMapping("/settings")
    public JsonNode settings() {
        return config.settings();
    }

    @PutMapping("/settings")
    public JsonNode putSettings(@RequestBody(required = false) JsonNode body) {
        return config.putSettings(body);
    }

    @GetMapping("/instructions/{scope}")
    public List<Map<String, Object>> instructionList(@PathVariable String scope,
                                                     @RequestParam(required = false) String owner) {
        return config.instructionList(scope, owner);
    }

    @GetMapping("/instructions/{scope}/{name}")
    public Map<String, Object> instruction(@PathVariable String scope, @PathVariable String name,
                                           @RequestParam(required = false) String owner) {
        return config.instruction(scope, owner, name);
    }

    @PutMapping("/instructions/{scope}/{name}")
    public ResponseEntity<Map<String, Object>> putInstruction(@PathVariable String scope, @PathVariable String name,
                                                              @RequestParam(required = false) String owner,
                                                              @RequestBody(required = false) Map<String, Object> body) {
        return Responses.entity(config.putInstruction(scope, owner, name, body));
    }

    @DeleteMapping("/instructions/{scope}/{name}")
    public ResponseEntity<Void> deleteInstruction(@PathVariable String scope, @PathVariable String name,
                                                  @RequestParam(required = false) String owner) {
        config.deleteInstruction(scope, owner, name);
        return Responses.noContent();
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> agentList() {
        return config.agentList();
    }

    @GetMapping("/agents/{name}")
    public Map<String, Object> agent(@PathVariable String name) {
        return config.agent(name);
    }

    @PutMapping("/agents/{name}")
    public ResponseEntity<Map<String, Object>> putAgent(@PathVariable String name,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        return Responses.entity(config.putAgent(name, body));
    }

    @DeleteMapping("/agents/{name}")
    public ResponseEntity<Void> deleteAgent(@PathVariable String name) {
        config.deleteAgent(name);
        return Responses.noContent();
    }
}
