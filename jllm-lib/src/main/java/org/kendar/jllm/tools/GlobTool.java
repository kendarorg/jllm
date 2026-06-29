package org.kendar.jllm.tools;

import org.kendar.jllm.exceptions.LLMToolException;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class GlobTool extends AbstractFileTool {

  public GlobTool(Path root) {
    super(root);
  }

  @Override
  public String name() {
    return "glob";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder("glob", "Find files matching a glob pattern relative to the working directory.")
        .prop("pattern", "string", "Glob pattern (e.g. '**/*.java').", true)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String pattern = requiredString(args, "pattern");
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    try (Stream<Path> stream = Files.walk(root)) {
      List<String> matches = new ArrayList<>();
      stream.filter(Files::isRegularFile).forEach(p -> {
        Path rel = root.relativize(p);
        if (matcher.matches(rel)) {
          matches.add(rel.toString());
        }
      });
      Collections.sort(matches);
      return String.join("\n", matches);
    } catch (IOException e) {
      throw new LLMToolException("Unable to glob", e);
    }
  }
}
