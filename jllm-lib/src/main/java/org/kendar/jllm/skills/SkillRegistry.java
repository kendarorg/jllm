package org.kendar.jllm.skills;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers and holds {@link Skill}s parsed from {@code SKILL.md} files found in
 * {@code <dir>/.jllm/skills/&#42;/SKILL.md}.
 */
public class SkillRegistry {

  private final Map<String, Skill> skills = new LinkedHashMap<>();

  /**
   * Scans extra dirs first, then the working dir, so that on a name clash the
   * working-dir skill (registered last) overrides the extra-dir one.
   */
  public void discover(Path workingDir, List<Path> extraDirs) {
    if (extraDirs != null) {
      for (Path extra : extraDirs) {
        if (extra != null) {
          scan(extra);
        }
      }
    }
    if (workingDir != null) {
      scan(workingDir); // workingDir wins on clash
    }
  }

  private void scan(Path baseDir) {
    Path skillsDir = baseDir.resolve(".jllm").resolve("skills");
    if (!Files.isDirectory(skillsDir)) {
      return;
    }
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(skillsDir)) {
      for (Path folder : dirs) {
        if (!Files.isDirectory(folder)) {
          continue;
        }
        Path skillFile = folder.resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) {
          continue;
        }
        Skill skill = parse(skillFile);
        if (skill != null) {
          skills.put(skill.getName().toLowerCase(), skill);
        }
      }
    } catch (IOException ignored) {
      // skip unreadable skills directory gracefully
    }
  }

  Skill parse(Path skillFile) {
    String content;
    try {
      content = Files.readString(skillFile);
    } catch (IOException e) {
      return null;
    }

    Skill skill = new Skill();
    skill.setPath(skillFile);
    skill.setDescription("");
    skill.setName(folderName(skillFile));

    String[] split = splitFrontmatter(content);
    String frontmatter = split[0];
    String body = split[1];
    skill.setBody(body);

    if (frontmatter != null) {
      for (String line : frontmatter.split("\n")) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
          continue;
        }
        String key = trimmed.substring(0, colon).trim().toLowerCase();
        String value = trimmed.substring(colon + 1).trim();
        switch (key) {
          case "name" -> {
            if (!value.isEmpty()) {
              skill.setName(value);
            }
          }
          case "description" -> skill.setDescription(value);
          case "allowed-tools" -> {
            List<String> tools = new ArrayList<>();
            for (String t : value.split(",")) {
              String tt = t.trim();
              if (!tt.isEmpty()) {
                tools.add(tt);
              }
            }
            skill.setAllowedTools(tools);
          }
          default -> {
            // ignore unknown keys
          }
        }
      }
    }
    return skill;
  }

  /**
   * Tiny frontmatter splitter. Returns {@code [frontmatter, body]} where
   * frontmatter is null if the content does not start with a {@code ---} line.
   */
  private String[] splitFrontmatter(String content) {
    String normalized = content.replace("\r\n", "\n");
    if (!normalized.startsWith("---\n") && !normalized.equals("---")) {
      return new String[]{null, content};
    }
    int firstNl = normalized.indexOf('\n');
    if (firstNl < 0) {
      return new String[]{null, content};
    }
    // Find the closing "---" line after the opening one.
    int searchFrom = firstNl + 1;
    int idx = searchFrom;
    while (idx < normalized.length()) {
      int lineEnd = normalized.indexOf('\n', idx);
      String line = (lineEnd < 0)
          ? normalized.substring(idx)
          : normalized.substring(idx, lineEnd);
      if (line.trim().equals("---")) {
        String frontmatter = normalized.substring(searchFrom, idx);
        String body = (lineEnd < 0) ? "" : normalized.substring(lineEnd + 1);
        return new String[]{frontmatter, body};
      }
      if (lineEnd < 0) {
        break;
      }
      idx = lineEnd + 1;
    }
    // no closing delimiter -> treat whole thing as body
    return new String[]{null, content};
  }

  private String folderName(Path skillFile) {
    Path parent = skillFile.getParent();
    return parent == null ? "skill" : parent.getFileName().toString();
  }

  public Skill get(String name) {
    if (name == null) {
      return null;
    }
    return skills.get(name.toLowerCase());
  }

  public Collection<Skill> all() {
    return skills.values();
  }

  /** Progressive disclosure: only {@code - name: description} lines, never bodies. */
  public String catalog() {
    StringBuilder sb = new StringBuilder();
    for (Skill s : skills.values()) {
      sb.append("- ").append(s.getName()).append(": ").append(s.getDescription()).append('\n');
    }
    return sb.toString();
  }
}
