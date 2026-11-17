package org.kendar.llm.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class LineCommandsTest {

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void exitAndExpandCommands() {
        assertTrue(LineCommands.isExitCommand(" /exit "));
        assertTrue(LineCommands.isExitCommand("/quit"));
        assertFalse(LineCommands.isExitCommand("/exit now"));
        assertTrue(LineCommands.isExpandCommand("/expand"));
        assertTrue(LineCommands.isExpandCommand("/expand 3"));
        assertFalse(LineCommands.isExpandCommand("/expanded"));
        assertEquals(" 3", LineCommands.expandArg("/expand 3"));
        assertEquals("", LineCommands.expandArg("/expand"));
    }

    @Test
    void yesAnswers() {
        assertTrue(LineCommands.startsWithYes(" Yes"));
        assertTrue(LineCommands.startsWithYes("y"));
        assertFalse(LineCommands.startsWithYes("n"));
        assertFalse(LineCommands.startsWithYes(LineCommands.EOF));
        assertEquals(Boolean.TRUE, LineCommands.parseYesNo("YES\n"));
        assertEquals(Boolean.FALSE, LineCommands.parseYesNo("yep"));
        assertEquals(Boolean.FALSE, LineCommands.parseYesNo(""));
        assertNull(LineCommands.parseYesNo(null));
    }

    @Test
    void plainLineReadsOnlyOneLine() throws IOException {
        var s = in("first\nsecond\n");
        assertEquals("first", LineCommands.readPlainLine(s));
        assertEquals("second", LineCommands.readPlainLine(s));
        assertNull(LineCommands.readPlainLine(s));
    }

    @Test
    void plainLineWithoutNewlineReturnsRest() throws IOException {
        assertEquals("tail", LineCommands.readPlainLine(in("tail")));
    }

    @Test
    void askYesNoPrintsQuestionAndParsesAnswer() {
        var buf = new ByteArrayOutputStream();
        var out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        assertEquals(Boolean.TRUE, LineCommands.askYesNo("Run it?", out, in("y\n")));
        assertEquals("Run it? [y/N] ", buf.toString(StandardCharsets.UTF_8));
        assertEquals(Boolean.FALSE, LineCommands.askYesNo("Run it?", out, in("no\n")));
        assertNull(LineCommands.askYesNo("Run it?", out, in("")));
    }
}
