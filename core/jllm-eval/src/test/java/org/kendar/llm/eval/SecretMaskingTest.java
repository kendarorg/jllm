package org.kendar.llm.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.eval.TestSupport.*;

/** A configured secret is masked everywhere it could be printed. */
@Tag("unit")
class SecretMaskingTest {

    private static final String SECRET = "sk-ant-SUPER-SECRET-123456";

    @Test
    void secretNeverAppearsInAnyPrintableForm(@TempDir Path dir) throws Exception {
        EvalConfig c = load(map("eval.anthropic.apiKey", SECRET, "eval.target", "remote.mac",
                "eval.remote.mac.apiKey", SECRET, "eval.remote.mac.baseUrl", "http://127.0.0.1:1/v1",
                "eval.timeout.connect", "2"), map());

        // records and objects
        assertFalse(c.target().toString().contains(SECRET));
        assertFalse(c.judge().toString().contains(SECRET));
        assertFalse(c.fast().toString().contains(SECRET));

        // exception messages, even when the secret sits inside the text
        assertFalse(new EvalConfigException(List.of("bad value " + SECRET), c.secrets()).getMessage().contains(SECRET));
        assertFalse(c.secrets().mask("Authorization: Bearer " + SECRET).contains(SECRET));

        // run metadata file
        RunMetadata meta = RunMetadata.of(c, new ConnectionCheck.Result(Map.of()), Instant.now());
        String json = Files.readString(meta.write(dir, c.secrets()));
        assertFalse(json.contains(SECRET), json);
        assertFalse(new ObjectMapper().writeValueAsString(meta).contains(SECRET));

        // connection failure messages
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ConnectionCheck.run(c, ep -> null));
        assertFalse(e.getMessage().contains(SECRET), e.getMessage());
    }

    @Test
    void maskReplacesEveryOccurrenceAndHandlesNulls() {
        Secrets s = new Secrets(List.of(SECRET));
        assertEquals("a *** b ***", s.mask("a " + SECRET + " b " + SECRET));
        assertNull(s.mask(null));
    }

    @Test
    void placeholderKeysAreNotSecrets() {
        Secrets s = new Secrets(List.of("none", "x", SECRET));
        assertEquals("none of them, ***", s.mask("none of them, " + SECRET));
    }

    @Test
    void maskJsonKeepsTheJsonValid() throws Exception {
        String secret = "nabcdefgh-123";
        Secrets s = new Secrets(List.of(secret));
        ObjectMapper m = new ObjectMapper();
        // a newline followed by "abcdefgh-123" serializes as \nabcdefgh-123: text, not the secret
        String text = "line\nabcdefgh-123 and " + secret;
        String json = s.maskJson(m.writeValueAsString(Map.of("t", text)));
        assertEquals("line\nabcdefgh-123 and ***", m.readTree(json).get("t").asText());
        assertEquals("{\"t\":\"\\\\***\"}", s.maskJson(m.writeValueAsString(Map.of("t", "\\" + secret))));
    }
}
