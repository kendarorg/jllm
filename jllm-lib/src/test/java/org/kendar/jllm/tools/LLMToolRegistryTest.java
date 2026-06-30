package org.kendar.jllm.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LLMToolRegistryTest {

  static class FakeTool implements LLMTool {
    private final String name;
    private final boolean avail;

    FakeTool(String name, boolean avail) {
      this.name = name;
      this.avail = avail;
    }

    @Override
    public boolean available() {
      return avail;
    }

    @Override
    public boolean requiresApproval() {
      return false;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String toolSchema() {
      return "{\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"description\":\"d\",\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}}";
    }

    @Override
    public String act(Map<String, String> args) throws LLMToolException {
      return "acted:" + name + ":" + args.get("x");
    }
  }

  @Test
  void getByNameAndAlias() {
    LLMToolRegistry registry = new LLMToolRegistry();
    registry.register(new FakeTool("edit", true));
    registry.registerAlias("replace", "edit");

    assertNotNull(registry.get("edit"));
    assertNotNull(registry.get("EDIT"));
    assertNotNull(registry.get("replace"));
    assertNull(registry.get("nope"));
  }

  @Test
  void dispatchRoutesToAct() {
    LLMToolRegistry registry = new LLMToolRegistry();
    registry.register(new FakeTool("edit", true));
    registry.registerAlias("replace", "edit");

    assertEquals("acted:edit:1", registry.dispatch("edit", Map.of("x", "1")));
    assertEquals("acted:edit:2", registry.dispatch("replace", Map.of("x", "2")));
  }

  static class NameDerivingTool extends FakeTool {
    NameDerivingTool() {
      super("task", true);
    }

    @Override
    public String nameDerivedArg() {
      return "agent";
    }

    @Override
    public String act(Map<String, String> args) {
      return "task:agent=" + args.get("agent");
    }
  }

  @Test
  void dispatchDerivesArgFromAlias() {
    LLMToolRegistry registry = new LLMToolRegistry();
    registry.register(new NameDerivingTool());
    registry.registerAlias("planning", "task");

    // Invoked via the agent-name alias with no explicit agent -> derived from name.
    assertEquals("task:agent=planning", registry.dispatch("planning", Map.of()));
    // Canonical name -> nothing derived.
    assertEquals("task:agent=null", registry.dispatch("task", Map.of()));
    // Explicit agent wins over the alias.
    assertEquals("task:agent=review", registry.dispatch("planning", Map.of("agent", "review")));
  }

  @Test
  void dispatchUnknownThrows() {
    LLMToolRegistry registry = new LLMToolRegistry();
    assertThrows(LLMToolException.class, () -> registry.dispatch("nope", Map.of()));
  }

  @Test
  void filteredRestrictsSet() {
    LLMToolRegistry registry = new LLMToolRegistry();
    registry.register(new FakeTool("edit", true));
    registry.register(new FakeTool("read_file", true));
    registry.registerAlias("replace", "edit");

    LLMToolRegistry filtered = registry.filtered(List.of("replace"));
    assertNotNull(filtered.get("edit"));
    assertNotNull(filtered.get("replace"));
    assertNull(filtered.get("read_file"));

    // null/empty -> all
    LLMToolRegistry all = registry.filtered(null);
    assertNotNull(all.get("edit"));
    assertNotNull(all.get("read_file"));
  }

  @Test
  void schemasSkipUnavailable() {
    LLMToolRegistry registry = new LLMToolRegistry();
    registry.register(new FakeTool("edit", true));
    registry.register(new FakeTool("hidden", false));

    List<ObjectNode> schemas = registry.schemas();
    assertEquals(1, schemas.size());
    assertEquals("edit", schemas.get(0).get("function").get("name").asText());
  }
}
