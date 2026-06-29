package org.kendar.jllm.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects context/memory files ({@code JLLM.md}, or its {@code QWEN.md} alias)
 * by walking from the working directory up to the filesystem root, plus any
 * extra directories. Files are concatenated nearest-last so the closest file
 * (the working directory) appears last and therefore "wins".
 */
public class ContextFileLoader {

  private static final String PRIMARY = "JLLM.md";
  private static final String ALIAS = "QWEN.md";

  private final Path workingDir;
  private final List<Path> extraDirs;

  public ContextFileLoader(Path workingDir, List<Path> extraDirs) {
    this.workingDir = workingDir.toAbsolutePath().normalize();
    this.extraDirs = extraDirs;
  }

  public String load() {
    // Collect from extra dirs first (lowest priority), then from root down to
    // the working dir so that the nearest file is concatenated last.
    List<Path> ordered = new ArrayList<>();

    if (extraDirs != null) {
      for (Path extra : extraDirs) {
        if (extra == null) {
          continue;
        }
        Path file = pick(extra.toAbsolutePath().normalize());
        if (file != null) {
          ordered.add(file);
        }
      }
    }

    // Walk upward collecting candidates, then reverse so root is first and
    // workingDir is last (nearest-last).
    List<Path> upward = new ArrayList<>();
    Path current = workingDir;
    while (current != null) {
      Path file = pick(current);
      if (file != null) {
        upward.add(file);
      }
      current = current.getParent();
    }
    for (int i = upward.size() - 1; i >= 0; i--) {
      ordered.add(upward.get(i));
    }

    StringBuilder sb = new StringBuilder();
    for (Path file : ordered) {
      String content = read(file);
      if (content == null) {
        continue;
      }
      sb.append("# --- ").append(file.toAbsolutePath().normalize()).append(" ---\n");
      sb.append(content);
      if (!content.endsWith("\n")) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  /** The target file for memory appends, regardless of existence. */
  public Path memoryFile() {
    return workingDir.resolve(PRIMARY);
  }

  private Path pick(Path dir) {
    Path primary = dir.resolve(PRIMARY);
    if (Files.isRegularFile(primary)) {
      return primary;
    }
    Path alias = dir.resolve(ALIAS);
    if (Files.isRegularFile(alias)) {
      return alias;
    }
    return null;
  }

  private String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      return null; // skip unreadable files gracefully
    }
  }
}
