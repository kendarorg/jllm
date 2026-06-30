package org.kendar.jllm.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.jllm.base.LLMObjectMapper;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the set of available {@link LLMTool}s and resolves both their canonical
 * (qwen) names and any registered aliases (e.g. gemini names).
 */
public class LLMToolRegistry {

  private final Map<String, LLMTool> tools = new LinkedHashMap<>();
  // alias (lowercased) -> canonical name (lowercased)
  private final Map<String, String> aliases = new LinkedHashMap<>();

  public void register(LLMTool tool) {
    if (tool == null) {
      throw new LLMToolException("Cannot register a null tool");
    }
    tools.put(tool.name().toLowerCase(), tool);
  }

  public void registerAlias(String alias, String canonicalName) {
    if (alias == null || canonicalName == null) {
      throw new LLMToolException("Alias and canonical name must not be null");
    }
    aliases.put(alias.toLowerCase(), canonicalName.toLowerCase());
  }

  public LLMTool get(String name) {
    if (name == null) {
      return null;
    }
    String key = name.toLowerCase();
    LLMTool tool = tools.get(key);
    if (tool != null) {
      return tool;
    }
    String canonical = aliases.get(key);
    if (canonical != null) {
      return tools.get(canonical);
    }
    return null;
  }

  public List<ObjectNode> schemas() {
    List<ObjectNode> result = new ArrayList<>();
    for (LLMTool tool : tools.values()) {
      if (!tool.available()) {
        continue;
      }
      try {
        result.add((ObjectNode) LLMObjectMapper.getObjectMapper().readTree(tool.toolSchema()));
      } catch (Exception e) {
        throw new LLMToolException("Unable to parse schema for tool " + tool.name(), e);
      }
    }
    return result;
  }

  public String dispatch(String name, Map<String, String> args) throws LLMToolException {
    LLMTool tool = get(name);
    if (tool == null) {
      throw new LLMToolException("Unknown tool: " + name);
    }
    // When the tool was reached through an alias (the invoked name differs from
    // its canonical name), let it recover an argument it folds into that name —
    // e.g. a sub-agent name routed to the single delegate tool.
    String derivedKey = tool.nameDerivedArg();
    if (derivedKey != null && name != null && !name.equalsIgnoreCase(tool.name())) {
      String existing = args == null ? null : args.get(derivedKey);
      if (existing == null || existing.isEmpty()) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (args != null) {
          merged.putAll(args);
        }
        merged.put(derivedKey, name);
        args = merged;
      }
    }
    return tool.act(args);
  }

  /**
   * Returns a NEW registry containing only the tools whose canonical name or
   * alias is in {@code allowedNames}. A null/empty allow-list returns all tools.
   * Aliases pointing at retained tools are preserved.
   */
  public LLMToolRegistry filtered(Collection<String> allowedNames) {
    LLMToolRegistry copy = new LLMToolRegistry();
    if (allowedNames == null || allowedNames.isEmpty()) {
      copy.tools.putAll(this.tools);
      copy.aliases.putAll(this.aliases);
      return copy;
    }
    // Normalize the allow-list and resolve aliases to canonical names.
    java.util.Set<String> allowedCanonical = new java.util.LinkedHashSet<>();
    for (String allowed : allowedNames) {
      if (allowed == null) {
        continue;
      }
      String key = allowed.toLowerCase();
      if (tools.containsKey(key)) {
        allowedCanonical.add(key);
      } else if (aliases.containsKey(key)) {
        allowedCanonical.add(aliases.get(key));
      }
    }
    for (Map.Entry<String, LLMTool> e : tools.entrySet()) {
      if (allowedCanonical.contains(e.getKey())) {
        copy.tools.put(e.getKey(), e.getValue());
      }
    }
    for (Map.Entry<String, String> e : aliases.entrySet()) {
      if (copy.tools.containsKey(e.getValue())) {
        copy.aliases.put(e.getKey(), e.getValue());
      }
    }
    return copy;
  }

  /** Registers all parity tools and aliases with no web-search endpoint. */
  public static LLMToolRegistry withDefaults(Path root) {
    return withDefaults(root, null);
  }

  /** Registers all parity tools, the web-search endpoint and the standard aliases. */
  public static LLMToolRegistry withDefaults(Path root, String webSearchEndpoint) {
    LLMToolRegistry registry = new LLMToolRegistry();
    registry.register(new ReadFileTool(root));
    registry.register(new WriteFileTool(root));
    registry.register(new EditFileTool(root));
    registry.register(new ListDirTool(root));
    registry.register(new GlobTool(root));
    registry.register(new GrepTool(root));
    registry.register(new ReadManyFilesTool(root));
    registry.register(new ShellTool(root));
    registry.register(new WebFetchTool());
    registry.register(new WebSearchTool(webSearchEndpoint));

    registry.registerAlias("replace", "edit");
    registry.registerAlias("ls", "list_directory");
    registry.registerAlias("grep", "search_file_content");
    registry.registerAlias("shell", "run_shell_command");
    return registry;
  }
}
