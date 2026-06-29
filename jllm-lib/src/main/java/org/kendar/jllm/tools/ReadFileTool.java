package org.kendar.jllm.tools;

import org.kendar.jllm.exceptions.LLMToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ReadFileTool extends AbstractFileTool {

  public ReadFileTool(Path root) {
    super(root);
  }

  @Override
  public String name() {
    return "read_file";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder("read_file", "Read the contents of a file, optionally a line range.")
        .prop("path", "string", "Path to the file, relative to the working directory.", true)
        .prop("offset", "integer", "1-based line number to start reading from.", false)
        .prop("limit", "integer", "Maximum number of lines to read.", false)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    Path file = resolveSafe(requiredString(args, "path"));
    if (!Files.exists(file) || Files.isDirectory(file)) {
      throw new LLMToolException("File not found: " + args.get("path"));
    }
    try {
      String offsetStr = optionalString(args, "offset", null);
      String limitStr = optionalString(args, "limit", null);
      if (offsetStr == null && limitStr == null) {
        return Files.readString(file, StandardCharsets.UTF_8);
      }
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      int offset = offsetStr == null ? 1 : Integer.parseInt(offsetStr);
      if (offset < 1) {
        offset = 1;
      }
      int from = offset - 1;
      if (from >= lines.size()) {
        return "";
      }
      int to = limitStr == null ? lines.size() : Math.min(lines.size(), from + Integer.parseInt(limitStr));
      return String.join("\n", lines.subList(from, to));
    } catch (NumberFormatException e) {
      throw new LLMToolException("offset/limit must be integers", e);
    } catch (IOException e) {
      throw new LLMToolException("Unable to read file: " + args.get("path"), e);
    }
  }
}
