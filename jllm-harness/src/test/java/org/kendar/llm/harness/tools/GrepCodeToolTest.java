package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.json;

class GrepCodeToolTest {

    @TempDir
    Path root;

    private String run(String args) throws Exception {
        HarnessTool t = new FileSystemAgentTools().tools().stream()
                .filter(x -> x.name().equals("grep_code")).findFirst().orElseThrow();
        return t.execute(new ToolContext("c1", root), json(args));
    }

    @Test
    void defaultListsDefinitionsInAnyLanguageAndSkipsBodiesAndBuildDirs() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("src/A.java"), String.join("\n",
                "package x;",
                "public class A {",
                "    public int f(int a) {",
                "        if (a > 0) {",
                "            return a;",
                "        }",
                "        return 0;",
                "    }",
                "}"));
        Files.writeString(root.resolve("src/b.py"), String.join("\n",
                "import os",
                "class B:",
                "    def g(self):",
                "        for x in y:",
                "            pass"));
        Files.writeString(root.resolve("target/junk.java"), "public class Junk {\n}\n");

        String out = run("{}");

        assertTrue(out.contains("## src/A.java (9 lines)"), out);
        assertTrue(out.contains("L2 public class A {"), out);
        assertTrue(out.contains("L3 public int f(int a) {"), out);
        assertFalse(out.contains("if (a > 0)"), out);
        assertTrue(out.contains("L2 class B:"), out);
        assertTrue(out.contains("L3 def g(self):"), out);
        assertFalse(out.contains("for x in y"), out);
        assertFalse(out.contains("Junk"), out);
    }

    @Test
    void patternOverridesTheDefaultAndGlobFilters() throws Exception {
        Files.writeString(root.resolve("a.go"), "package a\nfunc Foo() {\n}\nfunc Bar() {\n}\n");
        Files.writeString(root.resolve("b.txt"), "func Nope\n");

        String out = run("{'pattern':'^func ','glob':'**/*.go'}");

        assertTrue(out.contains("L2 func Foo() {"), out);
        assertTrue(out.contains("L4 func Bar() {"), out);
        assertFalse(out.contains("b.txt"), out);
    }
}
