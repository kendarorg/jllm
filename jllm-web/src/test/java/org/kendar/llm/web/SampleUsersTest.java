package org.kendar.llm.web;

import org.kendar.llm.web.users.FileUserRepository;
import org.kendar.llm.web.users.FileRoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.web.users.UserCredentialResolver;
import org.kendar.llm.web.users.Permissions;
import org.kendar.llm.web.commons.users.UserRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SampleUsersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static SimpleTool tool(String name, ToolKind kind) {
        return new SimpleTool(name, name, new ObjectMapper().createObjectNode(), "g", kind, (c, i) -> "");
    }

    /** The shipped permission files, copied into a temp folder so the test reads what the sample ships. */
    private static Permissions shipped(Path dir) throws Exception {
        Path src = Path.of("..", "runConfig", "webauth", "permissions");
        if (!Files.isDirectory(src)) src = Path.of("runConfig", "webauth", "permissions");
        for (String role : List.of("guest", "reader", "admin")) {
            Files.copy(src.resolve(role + ".json"), dir.resolve(role + ".json"));
        }
        return new Permissions(new FileRoleRepository(dir, new ObjectMapper()));
    }

    private static void user(Path dir, String id, String role, String credentials) throws Exception {
        Files.writeString(dir.resolve(id + ".json"),
                "{\"name\":\"" + id + "\",\"role\":\"" + role + "\",\"credentials\":" + credentials + "}");
    }

    @Test
    void guestReachesFilesOnlyThroughRunJsAndWeb(@TempDir Path dir) throws Exception {
        UserPolicy guest = shipped(dir).forRole("guest").orElseThrow();
        assertTrue(guest.allows(tool("run_js", ToolKind.OTHER)));
        assertTrue(guest.allows(tool("web_fetch", ToolKind.READ_ONLY)));
        assertFalse(guest.allows(tool("read_file", ToolKind.READ_ONLY)));
        assertFalse(guest.allows(tool("write_file", ToolKind.FILE_EDIT)));
    }

    @Test
    void readerHasReadOnlyFileToolsOnly(@TempDir Path dir) throws Exception {
        UserPolicy reader = shipped(dir).forRole("reader").orElseThrow();
        assertTrue(reader.allows(tool("read_file", ToolKind.READ_ONLY)));
        assertTrue(reader.allows(tool("grep", ToolKind.READ_ONLY)));
        assertFalse(reader.allows(tool("write_file", ToolKind.FILE_EDIT)));
        assertFalse(reader.allows(tool("edit_file", ToolKind.FILE_EDIT)));
        assertFalse(reader.allows(tool("run_js", ToolKind.OTHER)));
        assertFalse(reader.allows(tool("web_fetch", ToolKind.READ_ONLY)));
    }

    @Test
    void adminHasEveryToolWithoutApprovalPrompts(@TempDir Path dir) throws Exception {
        UserPolicy admin = shipped(dir).forRole("admin").orElseThrow();
        assertTrue(admin.allows(tool("write_file", ToolKind.FILE_EDIT)));
        assertTrue(admin.allows(tool("anything", ToolKind.OTHER)));
        assertFalse(admin.approval().requiresApproval(tool("write_file", ToolKind.FILE_EDIT)));
    }

    @Test
    void roleWithoutFileGetsNothing(@TempDir Path dir) throws Exception {
        Permissions perms = shipped(dir);
        assertTrue(perms.forRole("wizard").isEmpty());
        assertFalse(perms.resolver().resolve(HarnessPrincipal.of("x")).allows(tool("read_file", ToolKind.READ_ONLY)));
        Files.writeString(dir.resolve("broken.json"), "{\"tools\": 3}");
        assertTrue(perms.forRole("broken").isEmpty());
    }

    @Test
    void credentialsComeFromEnvironmentReferencesOnly(@TempDir Path dir) throws Exception {
        user(dir, "rita", "guest", "{\"mcp:docs\":{\"Authorization\":\"env:PATH\",\"X-Literal\":\"secret\"},"
                + "\"api:pets\":{\"X-Key\":\"env:JLLM_SAMPLE_UNSET_VARIABLE\"}}");
        UserCredentialResolver r = new UserCredentialResolver(new FileUserRepository(dir, mapper));
        HarnessPrincipal rita = HarnessPrincipal.of("rita");

        Map<String, String> docs = r.headers(rita, "mcp:docs");
        assertEquals(System.getenv("PATH"), docs.get("Authorization"));
        assertFalse(docs.containsKey("X-Literal"));
        assertEquals(Map.of(), r.headers(rita, "api:pets"));
        assertEquals(Map.of(), r.headers(HarnessPrincipal.of("nobody"), "mcp:docs"));
        assertEquals(Map.of(), r.headers(null, "mcp:docs"));
    }

    @Test
    void unknownUserIdsAreRejected(@TempDir Path dir) throws Exception {
        user(dir, "rita", "guest", "{}");
        UserRepository users = new FileUserRepository(dir, mapper);
        assertTrue(users.find("rita").isPresent());
        assertTrue(users.find("../rita").isEmpty());
        assertTrue(users.find("Rita").isEmpty());
        assertEquals(1, users.all().size());
    }

    @Test
    void chatWithoutPrincipalGetsNoTools(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("reader.json"), "{\"tools\":[\"*\"]}");
        var resolver = new Permissions(new FileRoleRepository(dir, mapper)).resolver();
        var anyTool = tool("read_file", ToolKind.READ_ONLY);
        assertFalse(resolver.resolve(null).allows(anyTool));
        assertTrue(resolver.resolve(new HarnessPrincipal("u", Set.of("reader"), Map.of())).allows(anyTool));
        var identity = new org.kendar.llm.harness.auth.Identity(null, resolver, null, chat -> null);
        assertFalse(identity.policyOf("c").allows(anyTool));
    }
}
