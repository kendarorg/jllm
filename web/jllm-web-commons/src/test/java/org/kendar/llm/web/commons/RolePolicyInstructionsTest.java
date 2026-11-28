package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.settings.InstructionRepository;
import org.kendar.llm.web.commons.users.Permissions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The role decides whether the instruction files of a chat directory count, and carries the role and user instructions. */
class RolePolicyInstructionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Role role(Map<String, com.fasterxml.jackson.databind.JsonNode> settings) {
        return new Role("dev", List.of("*"), false, "ask", Map.of(), settings);
    }

    @Test
    void scopedInstructionsAreOffUnlessTheRoleEnablesThem() {
        User user = new User("ann", "Ann", "dev", Map.of());
        assertEquals(UserPolicy.Scoped.OFF, Permissions.policyOf(role(Map.of()), user, null, InstructionRepository.NONE).scoped());
        assertEquals(UserPolicy.Scoped.OFF, role(Map.of()).policy().scoped());
        Map<String, com.fasterxml.jackson.databind.JsonNode> on = Map.of(Role.SCOPED_INSTRUCTIONS, MAPPER.getNodeFactory().booleanNode(true));
        assertEquals(UserPolicy.Scoped.USER_CONTENT, Permissions.policyOf(role(on), user, null, InstructionRepository.NONE).scoped());
        assertEquals(UserPolicy.Scoped.USER_CONTENT, role(on).policy().scoped(), "labelled as user content");
        Map<String, com.fasterxml.jackson.databind.JsonNode> off = Map.of(Role.SCOPED_INSTRUCTIONS, MAPPER.getNodeFactory().booleanNode(false));
        assertEquals(UserPolicy.Scoped.OFF, Permissions.policyOf(role(off), user, null, InstructionRepository.NONE).scoped());
    }

    @Test
    void thePolicyCarriesTheRoleAndUserInstructions() {
        InstructionRepository repo = new InstructionRepository() {
            public List<Instruction> list(Scope scope, String owner) {
                return switch (scope) {
                    case ROLE -> owner.equals("dev") ? List.of(new Instruction(scope, owner, "style", "Short.")) : List.of();
                    case USER -> owner.equals("ann") ? List.of(new Instruction(scope, owner, "me", "Ann.")) : List.of();
                    default -> List.of();
                };
            }

            public Optional<Instruction> find(Scope scope, String owner, String name) {
                return Optional.empty();
            }

            public Instruction write(Scope scope, String owner, String name, String content) {
                throw new UnsupportedOperationException();
            }

            public boolean delete(Scope scope, String owner, String name) {
                return false;
            }

            public void deleteOwned(Scope scope, String owner) {
            }
        };
        var policy = Permissions.policyOf(role(Map.of()), new User("ann", "Ann", "dev", Map.of()), null, repo);
        String text = policy.instructionsText();
        assertTrue(text.contains("Short.") && text.contains("Ann."), text);
        var other = Permissions.policyOf(role(Map.of()), new User("bob", "Bob", "dev", Map.of()), null, repo);
        assertFalse(other.instructionsText().contains("Ann."));
        assertEquals("", UserPolicy.ALL.instructionsText());
        assertEquals(UserPolicy.Scoped.ON, UserPolicy.ALL.scoped(), "the command line keeps them");
    }
}
