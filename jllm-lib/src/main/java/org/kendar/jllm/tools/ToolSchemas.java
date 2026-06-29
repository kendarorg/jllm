package org.kendar.jllm.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.jllm.base.LLMObjectMapper;
import org.kendar.jllm.exceptions.LLMToolException;

/** Small helper to build Ollama function-schema JSON without hand-concatenated strings. */
final class ToolSchemas {

  private ToolSchemas() {
  }

  static Builder builder(String name, String description) {
    return new Builder(name, description);
  }

  static final class Builder {
    private final ObjectNode properties;
    private final ArrayNode required;
    private final ObjectNode root;

    private Builder(String name, String description) {
      root = LLMObjectMapper.getObjectMapper().createObjectNode();
      root.put("type", "function");
      ObjectNode function = root.putObject("function");
      function.put("name", name);
      function.put("description", description);
      ObjectNode parameters = function.putObject("parameters");
      parameters.put("type", "object");
      properties = parameters.putObject("properties");
      required = parameters.putArray("required");
    }

    Builder prop(String name, String type, String description, boolean isRequired) {
      ObjectNode p = properties.putObject(name);
      p.put("type", type);
      p.put("description", description);
      if (isRequired) {
        required.add(name);
      }
      return this;
    }

    String build() {
      try {
        return LLMObjectMapper.getObjectMapper().writeValueAsString(root);
      } catch (Exception e) {
        throw new LLMToolException("Unable to serialize tool schema", e);
      }
    }
  }
}
