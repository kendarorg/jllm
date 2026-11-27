package org.kendar.llm.harness.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VirtualConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The defaults of the interface over a list. */
    private static final class Memory implements InstructionRepository {
        final List<Instruction> all = new ArrayList<>();

        public List<Instruction> list(Scope scope, String owner) {
            return all.stream().filter(i -> i.scope() == scope && i.owner().equals(owner == null ? "" : owner)).toList();
        }

        public Optional<Instruction> find(Scope scope, String owner, String name) {
            return list(scope, owner).stream().filter(i -> i.name().equals(name)).reduce((a, b) -> b);
        }

        public Instruction write(Scope scope, String owner, String name, String content) {
            InstructionRepository.check(scope, owner, name, content);
            Instruction i = new Instruction(scope, owner, name, content);
            all.add(i);
            return i;
        }

        public boolean delete(Scope scope, String owner, String name) {
            return false;
        }

        public void deleteOwned(Scope scope, String owner) {
        }
    }

    @Test
    void settingsThatNameFilesAreFound() throws Exception {
        var tree = MAPPER.readTree("{\"instructionFiles\":[\"A.md\"],"
                + "\"agents\":[{\"name\":\"a\",\"prompt\":\"x\"},{\"name\":\"b\",\"promptFile\":\"b.md\"}],"
                + "\"skills\":[{\"name\":\"s\",\"path\":\"s.md\"},{\"name\":\"t\"}]}");
        assertEquals(List.of("instructionFiles", "agents[1].promptFile", "skills[0].path"), PathSettings.find(tree));
        assertThrows(IllegalArgumentException.class, () -> PathSettings.check(tree, "x"));
        assertEquals(List.of(), PathSettings.find(MAPPER.readTree("{\"agents\":[{\"name\":\"a\",\"prompt\":\"x\"}]}")));
        PathSettings.check(MAPPER.readTree("{\"smallModel\":true}"), "x");
    }

    @Test
    void anAgentWithAPromptFileIsRefused() {
        AgentRepository.check(new AgentSettings("ok", null, null, "prompt", null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> AgentRepository.check(
                new AgentSettings("bad", null, null, null, "p.md", null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> AgentRepository.check(
                new AgentSettings("Bad Name", null, null, "p", null, null, null, null, null, null, null)));
    }

    @Test
    void instructionNamesOwnersAndSizesAreChecked() {
        InstructionRepository.check(InstructionRepository.Scope.GLOBAL, "", "AGENTS.md", "x");
        InstructionRepository.check(InstructionRepository.Scope.ROLE, "dev", "style", "x");
        InstructionRepository.check(InstructionRepository.Scope.USER, "ann", "style", "x");
        InstructionRepository.check(InstructionRepository.Scope.SYSTEM, "", "replace", "x");
        assertThrows(IllegalArgumentException.class, () -> InstructionRepository.check(
                InstructionRepository.Scope.SYSTEM, "", "other", "x"));
        assertThrows(IllegalArgumentException.class, () -> InstructionRepository.check(
                InstructionRepository.Scope.GLOBAL, "someone", "a", "x"));
        assertThrows(IllegalArgumentException.class, () -> InstructionRepository.check(
                InstructionRepository.Scope.ROLE, "", "a", "x"));
        assertThrows(IllegalArgumentException.class, () -> InstructionRepository.check(
                InstructionRepository.Scope.GLOBAL, "", "../x", "x"));
        assertThrows(IllegalArgumentException.class, () -> InstructionRepository.check(
                InstructionRepository.Scope.GLOBAL, "", "big", "x".repeat(InstructionRepository.MAX_BYTES + 1)));
    }

    @Test
    void theSystemPromptAndInstructionsAreRendered() {
        Memory m = new Memory();
        assertTrue(m.systemPromptReplace().isEmpty());
        assertEquals("", m.systemPromptAppend());
        assertEquals("", m.renderGlobal());
        m.write(InstructionRepository.Scope.SYSTEM, "", "replace", "  ");
        assertTrue(m.systemPromptReplace().isEmpty(), "blank counts as none");
        m.write(InstructionRepository.Scope.SYSTEM, "", "replace", "You are X.");
        m.write(InstructionRepository.Scope.SYSTEM, "", "append", "Be kind.");
        m.write(InstructionRepository.Scope.GLOBAL, "", "AGENTS.md", "Rule one.");
        m.write(InstructionRepository.Scope.ROLE, "dev", "style", "Short.");
        m.write(InstructionRepository.Scope.USER, "ann", "me", "Call me Ann.");
        assertEquals(Optional.of("You are X."), m.systemPromptReplace());
        assertEquals("Be kind.", m.systemPromptAppend());
        assertEquals("Instructions from AGENTS.md:\nRule one.", m.renderGlobal());
        String chat = m.renderFor("dev", "ann");
        assertTrue(chat.contains("Instructions for role dev (style):\nShort."), chat);
        assertTrue(chat.contains("Instructions for this user (me):\nCall me Ann."), chat);
        assertEquals("", m.renderFor("other", "bob"));
        assertEquals("", m.renderFor(null, null));
    }
}
