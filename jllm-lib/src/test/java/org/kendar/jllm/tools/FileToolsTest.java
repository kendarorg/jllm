package org.kendar.jllm.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {

  @TempDir
  Path root;

  @Test
  void writeThenRead() {
    WriteFileTool write = new WriteFileTool(root);
    String res = write.act(Map.of("path", "sub/dir/file.txt", "content", "hello\nworld\n"));
    assertTrue(res.contains("bytes"));
    assertTrue(Files.exists(root.resolve("sub/dir/file.txt")));

    ReadFileTool read = new ReadFileTool(root);
    assertEquals("hello\nworld\n", read.act(Map.of("path", "sub/dir/file.txt")));
  }

  @Test
  void readWithOffsetAndLimit() {
    new WriteFileTool(root).act(Map.of("path", "lines.txt", "content", "l1\nl2\nl3\nl4\nl5"));
    ReadFileTool read = new ReadFileTool(root);
    assertEquals("l2\nl3", read.act(Map.of("path", "lines.txt", "offset", "2", "limit", "2")));
  }

  @Test
  void editUniqueAndOccurrenceMismatch() {
    new WriteFileTool(root).act(Map.of("path", "e.txt", "content", "foo bar foo"));
    EditFileTool edit = new EditFileTool(root);

    // occurrence != expected (default 1, but there are 2)
    assertThrows(LLMToolException.class, () -> edit.act(Map.of("path", "e.txt", "old_string", "foo", "new_string", "X")));

    // explicit expected matches
    String res = edit.act(Map.of("path", "e.txt", "old_string", "foo", "new_string", "X", "expected_replacements", "2"));
    assertTrue(res.contains("2"));
    assertEquals("X bar X", new ReadFileTool(root).act(Map.of("path", "e.txt")));
  }

  @Test
  void listDirectory() {
    new WriteFileTool(root).act(Map.of("path", "a.txt", "content", "a"));
    new WriteFileTool(root).act(Map.of("path", "d/b.txt", "content", "b"));
    String res = new ListDirTool(root).act(Map.of());
    assertTrue(res.contains("a.txt"));
    assertTrue(res.contains("d/"));
  }

  @Test
  void globFinds() {
    new WriteFileTool(root).act(Map.of("path", "src/Main.java", "content", "x"));
    new WriteFileTool(root).act(Map.of("path", "readme.md", "content", "x"));
    String res = new GlobTool(root).act(Map.of("pattern", "**/*.java"));
    assertTrue(res.contains("Main.java"));
    assertFalse(res.contains("readme.md"));
  }

  @Test
  void grepReturnsMatches() {
    new WriteFileTool(root).act(Map.of("path", "g.txt", "content", "alpha\nbeta\ngamma"));
    String res = new GrepTool(root).act(Map.of("pattern", "beta"));
    assertTrue(res.contains("g.txt:2:beta"));
  }

  @Test
  void readManyBatches() {
    new WriteFileTool(root).act(Map.of("path", "x.txt", "content", "X"));
    new WriteFileTool(root).act(Map.of("path", "y.txt", "content", "Y"));
    String res = new ReadManyFilesTool(root).act(Map.of("paths", "x.txt, y.txt"));
    assertTrue(res.contains("=== x.txt ==="));
    assertTrue(res.contains("=== y.txt ==="));
    assertTrue(res.contains("X"));
    assertTrue(res.contains("Y"));
  }

  @Test
  void sandboxBlocksEscapes() {
    String absoluteOutside = root.getParent().resolve("escape.txt").toAbsolutePath().toString();
    List<LLMTool> fileTools = List.of(
        new ReadFileTool(root),
        new WriteFileTool(root),
        new EditFileTool(root),
        new ListDirTool(root),
        new ReadManyFilesTool(root));

    for (LLMTool tool : fileTools) {
      assertThrows(LLMToolException.class,
          () -> tool.act(makeArgs(tool.name(), "../escape.txt")),
          tool.name() + " should reject ../escape.txt");
      assertThrows(LLMToolException.class,
          () -> tool.act(makeArgs(tool.name(), absoluteOutside)),
          tool.name() + " should reject absolute path outside root");
    }
  }

  private Map<String, String> makeArgs(String toolName, String path) {
    return switch (toolName) {
      case "write_file" -> Map.of("path", path, "content", "x");
      case "edit" -> Map.of("path", path, "old_string", "a", "new_string", "b");
      case "read_many_files" -> Map.of("paths", path);
      default -> Map.of("path", path);
    };
  }
}
