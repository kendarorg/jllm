package org.kendar.jllm.tools;

import org.kendar.jllm.exceptions.LLMToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class WriteFileTool extends AbstractFileTool {

  public WriteFileTool(Path root) {
    super(root);
  }

  @Override
  public boolean requiresApproval() {
    return true;
  }

  @Override
  public String name() {
    return "write_file";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder("write_file", "Write content to a file, creating parent directories and overwriting any existing file.")
        .prop("path", "string", "Path to the file, relative to the working directory.", true)
        .prop("content", "string", "The content to write.", true)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    Path file = resolveSafe(requiredString(args, "path"));
    String content = args.get("content");
    if (content == null) {
      content = "";
    }
    try {
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      Files.write(file, bytes);
      return "Wrote " + bytes.length + " bytes to " + relativize(file);
    } catch (IOException e) {
      throw new LLMToolException("Unable to write file: " + args.get("path"), e);
    }
  }
}
