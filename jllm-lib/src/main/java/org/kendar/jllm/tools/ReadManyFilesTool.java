package org.kendar.jllm.tools;

import org.kendar.jllm.exceptions.LLMToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class ReadManyFilesTool extends AbstractFileTool {

  public ReadManyFilesTool(Path root) {
    super(root);
  }

  @Override
  public String name() {
    return "read_many_files";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder("read_many_files", "Read multiple files at once. Accepts a comma- or newline-separated list of relative paths and/or glob patterns.")
        .prop("paths", "string", "Comma- or newline-separated list of relative paths and/or globs.", true)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String pathsArg = requiredString(args, "paths");
    String[] tokens = pathsArg.split("[,\\n]");
    Set<Path> files = new LinkedHashSet<>();
    for (String token : tokens) {
      String t = token.trim();
      if (t.isEmpty()) {
        continue;
      }
      if (t.contains("*") || t.contains("?")) {
        files.addAll(glob(t));
      } else {
        files.add(resolveSafe(t));
      }
    }
    StringBuilder sb = new StringBuilder();
    for (Path file : files) {
      sb.append("=== ").append(relativize(file)).append(" ===\n");
      if (!Files.isRegularFile(file)) {
        sb.append("[not found]\n");
        continue;
      }
      try {
        sb.append(Files.readString(file, StandardCharsets.UTF_8));
      } catch (IOException e) {
        sb.append("[unreadable]");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

  private List<Path> glob(String pattern) throws LLMToolException {
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    try (Stream<Path> stream = Files.walk(root)) {
      List<Path> matches = new ArrayList<>();
      stream.filter(Files::isRegularFile).forEach(p -> {
        if (matcher.matches(root.relativize(p))) {
          matches.add(p);
        }
      });
      Collections.sort(matches);
      return matches;
    } catch (IOException e) {
      throw new LLMToolException("Unable to expand glob: " + pattern, e);
    }
  }
}
