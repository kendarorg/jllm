package org.kendar.jllm.tools;

import org.kendar.jllm.exceptions.LLMToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class EditFileTool extends AbstractFileTool {

  public EditFileTool(Path root) {
    super(root);
  }

  @Override
  public boolean requiresApproval() {
    return true;
  }

  @Override
  public String name() {
    return "edit";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder("edit", "Replace an exact string in a file with a new string.")
        .prop("path", "string", "Path to the file, relative to the working directory.", true)
        .prop("old_string", "string", "The exact text to replace.", true)
        .prop("new_string", "string", "The replacement text.", true)
        .prop("expected_replacements", "integer", "Number of occurrences expected (default 1).", false)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    Path file = resolveSafe(requiredString(args, "path"));
    String oldString = requiredString(args, "old_string");
    String newString = args.get("new_string");
    if (newString == null) {
      newString = "";
    }
    int expected;
    try {
      expected = Integer.parseInt(optionalString(args, "expected_replacements", "1"));
    } catch (NumberFormatException e) {
      throw new LLMToolException("expected_replacements must be an integer", e);
    }
    if (!Files.exists(file) || Files.isDirectory(file)) {
      throw new LLMToolException("File not found: " + args.get("path"));
    }
    try {
      String content = Files.readString(file, StandardCharsets.UTF_8);
      int occurrences = countOccurrences(content, oldString);
      if (occurrences != expected) {
        throw new LLMToolException("Expected " + expected + " occurrence(s) of old_string but found "
            + occurrences + " in " + relativize(file));
      }
      String updated = content.replace(oldString, newString);
      Files.writeString(file, updated, StandardCharsets.UTF_8);
      return "Replaced " + occurrences + " occurrence(s) in " + relativize(file);
    } catch (IOException e) {
      throw new LLMToolException("Unable to edit file: " + args.get("path"), e);
    }
  }

  private int countOccurrences(String content, String needle) {
    if (needle.isEmpty()) {
      return 0;
    }
    int count = 0;
    int idx = 0;
    while ((idx = content.indexOf(needle, idx)) != -1) {
      count++;
      idx += needle.length();
    }
    return count;
  }
}
