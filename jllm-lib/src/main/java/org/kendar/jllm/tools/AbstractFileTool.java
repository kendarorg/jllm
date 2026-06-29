package org.kendar.jllm.tools;

import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;

import java.nio.file.Path;
import java.util.Map;

/**
 * Base class for tools that operate on the filesystem. Centralizes the
 * working-directory sandbox so paths cannot escape the configured root.
 */
public abstract class AbstractFileTool implements LLMTool {

  protected final Path root;

  protected AbstractFileTool(Path root) {
    if (root == null) {
      throw new LLMToolException("root path must not be null");
    }
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public boolean requiresApproval() {
    return false;
  }

  /**
   * Resolves {@code relative} against the root, normalizes it and throws if the
   * result escapes the sandbox root.
   */
  protected Path resolveSafe(String relative) throws LLMToolException {
    if (relative == null) {
      relative = "";
    }
    Path resolved = root.resolve(relative).toAbsolutePath().normalize();
    if (!resolved.startsWith(root)) {
      throw new LLMToolException("Path escapes the working directory: " + relative);
    }
    return resolved;
  }

  protected String requiredString(Map<String, String> args, String key) throws LLMToolException {
    String value = args == null ? null : args.get(key);
    if (value == null || value.isEmpty()) {
      throw new LLMToolException("Missing required argument: " + key);
    }
    return value;
  }

  protected String optionalString(Map<String, String> args, String key, String def) {
    String value = args == null ? null : args.get(key);
    return (value == null || value.isEmpty()) ? def : value;
  }

  protected String relativize(Path p) {
    return root.relativize(p).toString();
  }
}
