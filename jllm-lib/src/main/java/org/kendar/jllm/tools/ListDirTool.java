package org.kendar.jllm.tools;

import org.kendar.jllm.exceptions.LLMToolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ListDirTool extends AbstractFileTool {

  public ListDirTool(Path root) {
    super(root);
  }

  @Override
  public String name() {
    return "list_directory";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder("list_directory", "List the entries of a directory. Directories are marked with a trailing slash.")
        .prop("path", "string", "Directory path relative to the working directory (default '.').", false)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    Path dir = resolveSafe(optionalString(args, "path", "."));
    if (!Files.isDirectory(dir)) {
      throw new LLMToolException("Not a directory: " + optionalString(args, "path", "."));
    }
    try (Stream<Path> stream = Files.list(dir)) {
      List<String> entries = new ArrayList<>();
      stream.forEach(p -> entries.add(Files.isDirectory(p) ? p.getFileName() + "/" : p.getFileName().toString()));
      Collections.sort(entries);
      return String.join("\n", entries);
    } catch (IOException e) {
      throw new LLMToolException("Unable to list directory", e);
    }
  }
}
