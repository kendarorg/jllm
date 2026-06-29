package org.kendar.jllm.tools;

import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Appends a fact as a bullet under a {@code ## Added Memories} section in JLLM.md. */
public class SaveMemoryTool implements LLMTool {

  private static final String SECTION = "## Added Memories";

  private final Path memoryFile;

  public SaveMemoryTool(Path memoryFile) {
    this.memoryFile = memoryFile;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public boolean requiresApproval() {
    // Mutating-tool convention; default approval is auto.
    return true;
  }

  @Override
  public String name() {
    return "save_memory";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder(name(), "Persist a fact to the project memory file (JLLM.md).")
        .prop("fact", "string", "The fact to remember.", true)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String fact = args == null ? null : args.get("fact");
    if (fact == null || fact.isEmpty()) {
      throw new LLMToolException("Missing required argument: fact");
    }
    String bullet = "- " + fact.trim();
    try {
      String content = Files.exists(memoryFile)
          ? Files.readString(memoryFile).replace("\r\n", "\n")
          : "";

      String updated;
      int idx = sectionIndex(content);
      if (idx < 0) {
        StringBuilder sb = new StringBuilder(content);
        if (!content.isEmpty() && !content.endsWith("\n")) {
          sb.append('\n');
        }
        if (!content.isEmpty()) {
          sb.append('\n');
        }
        sb.append(SECTION).append("\n").append(bullet).append('\n');
        updated = sb.toString();
      } else {
        // insert the bullet at the end of the section (before the next "## " header)
        int sectionBodyStart = content.indexOf('\n', idx);
        if (sectionBodyStart < 0) {
          sectionBodyStart = content.length();
        }
        int nextHeader = content.indexOf("\n## ", sectionBodyStart);
        int insertAt = (nextHeader < 0) ? content.length() : nextHeader;
        String before = content.substring(0, insertAt);
        String after = content.substring(insertAt);
        StringBuilder sb = new StringBuilder(before);
        if (!before.endsWith("\n")) {
          sb.append('\n');
        }
        sb.append(bullet).append('\n');
        sb.append(after);
        updated = sb.toString();
      }

      if (memoryFile.getParent() != null) {
        Files.createDirectories(memoryFile.getParent());
      }
      Files.writeString(memoryFile, updated);
      return "Saved memory: " + fact.trim();
    } catch (IOException e) {
      throw new LLMToolException("Unable to write memory file: " + memoryFile, e);
    }
  }

  private int sectionIndex(String content) {
    if (content.startsWith(SECTION)) {
      return 0;
    }
    int idx = content.indexOf("\n" + SECTION);
    return idx < 0 ? -1 : idx + 1;
  }
}
