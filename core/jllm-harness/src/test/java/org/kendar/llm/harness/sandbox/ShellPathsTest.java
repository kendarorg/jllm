package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Shell command lines that climb out of the work dir are found; the ones that stay inside are not. */
class ShellPathsTest {

    @TempDir
    Path base;

    @Test
    void climbingOutIsFound() {
        Path work = base.resolve("work");
        assertEquals("..\\outside-secret.txt", ShellPaths.escapingPath("type ..\\outside-secret.txt", work, work));
        assertEquals("../../etc/passwd", ShellPaths.escapingPath("cat ../../etc/passwd", work, work));
        assertEquals("..", ShellPaths.escapingPath("cd .. && dir", work, work));
        assertEquals("../x", ShellPaths.escapingPath("echo hi >../x", work, work));
    }

    @Test
    void stayingInsideIsAllowed() {
        Path work = base.resolve("work");
        assertNull(ShellPaths.escapingPath("type src\\..\\a.txt", work, work));
        assertNull(ShellPaths.escapingPath("git log --oneline", work, work));
        assertNull(ShellPaths.escapingPath("curl https://example.com/a/../b", work, work));
        assertNull(ShellPaths.escapingPath("echo ...", work, work));
    }

    @Test
    void scratchDirIsAlsoARoot() {
        Path work = base.resolve("work");
        Path scratch = base.resolve("scratch");
        assertNull(ShellPaths.escapingPath("type ..\\work\\a.txt", scratch, work, scratch));
        assertEquals("..\\other\\a.txt", ShellPaths.escapingPath("type ..\\other\\a.txt", scratch, work, scratch));
    }
}
