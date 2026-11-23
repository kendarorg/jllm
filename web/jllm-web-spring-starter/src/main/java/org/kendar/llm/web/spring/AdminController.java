package org.kendar.llm.web.spring;

import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.admin.AdminApi;
import org.kendar.llm.web.commons.admin.ToolCatalogView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** {@code /api/admin/**}: binds the request, calls the {@link AdminApi}. The admin gate has already run. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminApi admin;

    public AdminController(AdminApi admin) {
        this.admin = admin;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> userList() {
        return admin.userList();
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody(required = false) Map<String, Object> body) {
        return Responses.entity(admin.createUser(body));
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> updateUser(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return admin.updateUser(id, body);
    }

    @PostMapping("/users/{id}/password")
    public ResponseEntity<Void> setPassword(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        admin.setPassword(id, body);
        return Responses.noContent();
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id,
                                           @RequestAttribute(value = Principal.ATTR, required = false) Principal me) {
        admin.deleteUser(id, me);
        return Responses.noContent();
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> roleList() {
        return admin.roleList();
    }

    @PostMapping("/roles")
    public ResponseEntity<Map<String, Object>> createRole(@RequestBody(required = false) Map<String, Object> body) {
        return Responses.entity(admin.createRole(body));
    }

    @PutMapping("/roles/{name}")
    public Map<String, Object> updateRole(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body) {
        return admin.updateRole(name, body);
    }

    @DeleteMapping("/roles/{name}")
    public ResponseEntity<Void> deleteRole(@PathVariable String name) {
        admin.deleteRole(name);
        return Responses.noContent();
    }

    @GetMapping("/catalog")
    public ToolCatalogView.Catalog catalog() {
        return admin.catalog();
    }

    @GetMapping("/skills")
    public List<Map<String, Object>> skillList() {
        return admin.skillList();
    }

    @GetMapping("/skills/{name}")
    public Map<String, Object> skill(@PathVariable String name) {
        return admin.skill(name);
    }

    @PostMapping("/skills")
    public ResponseEntity<Map<String, Object>> createSkill(@RequestBody(required = false) Map<String, Object> body) {
        return Responses.entity(admin.createSkill(body));
    }

    @PutMapping("/skills/{name}")
    public Map<String, Object> updateSkill(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body) {
        return admin.updateSkill(name, body);
    }

    @DeleteMapping("/skills/{name}")
    public ResponseEntity<Void> deleteSkill(@PathVariable String name) {
        admin.deleteSkill(name);
        return Responses.noContent();
    }
}
