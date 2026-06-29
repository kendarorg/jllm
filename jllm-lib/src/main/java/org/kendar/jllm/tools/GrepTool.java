package org.kendar.jllm.tools;

import org.kendar.jllm.exceptions.LLMToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public class GrepTool extends AbstractFileTool {

  public GrepTool(Path root) {
    super(root);
  }

  @Override
  public String name() {
    return "search_file_content";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder("search_file_content", "Search file contents for a regular expression. Returns relpath:lineNo:line matches.")
        .prop("pattern", "string", "Regular expression to search for.", true)
        .prop("path", "string", "File or directory to search (default working directory).", false)
        .prop("include", "string", "Glob to filter which files are searched (e.g. '*.java').", false)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String patternStr = requiredString(args, "pattern");
    Pattern pattern;
    try {
      pattern = Pattern.compile(patternStr);
    } catch (PatternSyntaxException e) {
      throw new LLMToolException("Invalid regex: " + patternStr, e);
    }
    Path start = resolveSafe(optionalString(args, "path", "."));
    String include = optionalString(args, "include", null);
    PathMatcher includeMatcher = include == null
        ? null
        : FileSystems.getDefault().getPathMatcher("glob:" + include);

    List<String> results = new ArrayList<>();
    if (Files.isRegularFile(start)) {
      searchFile(start, pattern, includeMatcher, results);
      return String.join("\n", results);
    }
    try (Stream<Path> stream = Files.walk(start)) {
      stream.filter(Files::isRegularFile)
          .forEach(p -> searchFile(p, pattern, includeMatcher, results));
    } catch (IOException e) {
      throw new LLMToolException("Unable to search", e);
    }
    return String.join("\n", results);
  }

  private void searchFile(Path file, Pattern pattern, PathMatcher includeMatcher, List<String> results) {
    if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
      return;
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return; // skip binary/unreadable files
    }
    String rel = relativize(file);
    for (int i = 0; i < lines.size(); i++) {
      if (pattern.matcher(lines.get(i)).find()) {
        results.add(rel + ":" + (i + 1) + ":" + lines.get(i));
      }
    }
  }
}
