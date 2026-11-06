package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.kendar.llm.harness.HarnessTestSupport.json;

class FileSystemAgentToolsJailTest {

    @TempDir
    Path tmp;

    Path root;
    Path outside;
    ToolContext ctx;
    FileSystemAgentTools fs = new FileSystemAgentTools();

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("work"));
        outside = Files.createDirectories(tmp.resolve("work-evil")); // shares the textual prefix of root
        Files.writeString(outside.resolve("secret.txt"), "TOP SECRET");
        Files.writeString(tmp.resolve("outside.txt"), "TOP SECRET");
        Files.writeString(root.resolve("inside.txt"), "line1\nline2\nline3\n");
        ctx = new ToolContext("c1", root);
    }

    private HarnessTool tool(String name) {
        return fs.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void traversalVariantsAreRejected() {
        for (String p : new String[]{
                "../outside.txt", "..", "./../outside.txt", "a/../../outside.txt", "a/b/../../../outside.txt",
                "../work-evil/secret.txt",
                tmp.resolve("outside.txt").toString(), outside.resolve("secret.txt").toString()}) {
            assertThrows(IllegalArgumentException.class, () -> FileSystemAgentTools.jail(ctx, p), p);
        }
    }

    @Test
    void windowsBackslashTraversalIsRejected() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        assertThrows(IllegalArgumentException.class, () -> FileSystemAgentTools.jail(ctx, "..\\outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> FileSystemAgentTools.jail(ctx, "sub/..\\..\\outside.txt"));
    }

    @Test
    void benignDotSegmentsStayInside() {
        assertEquals(root.resolve("inside.txt").toAbsolutePath().normalize(), FileSystemAgentTools.jail(ctx, "./a/../inside.txt"));
        assertEquals(root.toAbsolutePath().normalize(), FileSystemAgentTools.jail(ctx, "."));
    }

    @Test
    void everyPathTakingToolRefusesToEscape() {
        String[] escapes = {"../outside.txt", "../work-evil/secret.txt"};
        for (String p : escapes) {
            assertThrows(IllegalArgumentException.class, () -> tool("read_file").execute(ctx, json("{'path':'" + p + "'}")));
            assertThrows(IllegalArgumentException.class,
                    () -> tool("write_file").execute(ctx, json("{'path':'" + p + "','content':'pwn'}")));
            assertThrows(IllegalArgumentException.class,
                    () -> tool("edit_file").execute(ctx, json("{'path':'" + p + "','old_string':'TOP','new_string':'x'}")));
            assertThrows(IllegalArgumentException.class,
                    () -> tool("grep").execute(ctx, json("{'pattern':'SECRET','path':'" + p + "'}")));
        }
        assertEquals("TOP SECRET", readQuietly(tmp.resolve("outside.txt")));
        assertEquals("TOP SECRET", readQuietly(outside.resolve("secret.txt")));
    }

    @Test
    void previewsAlsoRefuseEscapes() {
        assertThrows(IllegalArgumentException.class,
                () -> tool("write_file").preview(ctx, json("{'path':'../outside.txt','content':'x'}")));
    }

    @Test
    void globAndGrepNeverListOutsideFiles() throws Exception {
        String globbed = tool("glob").execute(ctx, json("{'pattern':'**/*.txt'}"));
        assertEquals("inside.txt", globbed);
        assertFalse(tool("glob").execute(ctx, json("{'pattern':'../**'}")).contains("secret"));
        assertEquals("", tool("grep").execute(ctx, json("{'pattern':'SECRET'}")));
    }

    @Test
    void normalOperationsStillWork() throws Exception {
        assertEquals("2\tline2\n", tool("read_file").execute(ctx, json("{'path':'inside.txt','offset':2,'limit':1}")));
        tool("write_file").execute(ctx, json("{'path':'deep/dir/new.txt','content':'abc'}"));
        assertEquals("abc", Files.readString(root.resolve("deep/dir/new.txt")));
        tool("edit_file").execute(ctx, json("{'path':'deep/dir/new.txt','old_string':'b','new_string':'B'}"));
        assertEquals("aBc", Files.readString(root.resolve("deep/dir/new.txt")));
        assertTrue(tool("grep").execute(ctx, json("{'pattern':'line2'}")).contains("inside.txt:2:line2"));
        assertThrows(IllegalArgumentException.class,
                () -> tool("edit_file").execute(ctx, json("{'path':'inside.txt','old_string':'line','new_string':'x'}")),
                "ambiguous replacement must be refused");
    }

    @Test
    void symlinkInsideWorkDirCannotPointOutside() throws Exception {
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception | Error e) {
            assumeTrue(false, "symlinks not permitted on this machine: " + e);
        }

        assertThrows(IllegalArgumentException.class, () -> FileSystemAgentTools.jail(ctx, "link/secret.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> tool("read_file").execute(ctx, json("{'path':'link/secret.txt'}")));
        assertThrows(IllegalArgumentException.class,
                () -> tool("write_file").execute(ctx, json("{'path':'link/planted.txt','content':'x'}")));
        assertFalse(Files.exists(outside.resolve("planted.txt")));
        assertFalse(tool("grep").execute(ctx, json("{'pattern':'SECRET'}")).contains("secret.txt"));
    }

    @Test
    void kindsAreAsDeclared() {
        List<HarnessTool> tools = fs.tools();
        assertEquals(ToolKind.FILE_EDIT, tool("write_file").kind());
        assertEquals(ToolKind.FILE_EDIT, tool("edit_file").kind());
        for (String ro : List.of("read_file", "glob", "grep", "grep_code")) assertEquals(ToolKind.READ_ONLY, tool(ro).kind());
        assertEquals(6, tools.size());
    }

    private static String readQuietly(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
