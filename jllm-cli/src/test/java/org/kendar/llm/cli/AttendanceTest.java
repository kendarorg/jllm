package org.kendar.llm.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Nobody can answer a question with {@code --prompt}, piped input or a CI environment. */
class AttendanceTest {

    private final InputStream savedIn = System.in;
    private final Function<String, String> savedEnv = Attendance.env;

    @BeforeEach
    void quiet() {
        System.setIn(new ByteArrayInputStream(new byte[0]));
        Attendance.env = name -> null;
    }

    @AfterEach
    void restore() {
        System.setIn(savedIn);
        Attendance.env = savedEnv;
    }

    private static boolean unattended(Map<String, String> args) throws Exception {
        return Attendance.unattended(args);
    }

    @Test
    void aTerminalWithoutPromptOrCiIsAttended() throws Exception {
        assertFalse(unattended(Map.of()));
    }

    @Test
    void promptIsUnattended() throws Exception {
        assertTrue(unattended(Map.of("prompt", "hi")));
    }

    @Test
    void pipedInputIsUnattended() throws Exception {
        System.setIn(new ByteArrayInputStream("script line\n".getBytes()));
        assertTrue(unattended(Map.of()));
    }

    @Test
    void ciEnvironmentIsUnattendedUnlessEmptyFalseOrZero() throws Exception {
        for (String ci : new String[]{"true", "1", "yes", "github"}) {
            Attendance.env = n -> n.equals("CI") ? ci : null;
            assertTrue(unattended(Map.of()), "CI=" + ci);
        }
        for (String ci : new String[]{"false", "FALSE", "0", "", "  "}) {
            Attendance.env = n -> n.equals("CI") ? ci : null;
            assertFalse(unattended(Map.of()), "CI='" + ci + "'");
        }
        Attendance.env = n -> null;
        assertFalse(unattended(Map.of()), "CI unset");
    }
}
