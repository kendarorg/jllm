package org.kendar.llm.eval.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class SampleToolsTest {

    private static final ObjectMapper M = new ObjectMapper();
    @TempDir
    Path dir;

    private static JsonNode in(String json) throws Exception {
        return M.readTree(json);
    }

    @Test
    void kindsAreHonest() {
        Map<String, ToolKind> kinds = new SampleTools().all().stream()
                .collect(Collectors.toMap(HarnessTool::name, HarnessTool::kind));
        assertEquals(ToolKind.READ_ONLY, kinds.get("time_offset"));
        assertEquals(ToolKind.READ_ONLY, kinds.get("flaky_lookup"));
        assertEquals(ToolKind.FILE_EDIT, kinds.get("write_note"));
        assertEquals(ToolKind.OTHER, kinds.get("send_email"));
    }

    @Test
    void toolsRunAndAreCounted() throws Exception {
        SampleTools t = new SampleTools();
        ToolContext ctx = new ToolContext("c", dir);
        Map<String, HarnessTool> by = t.all().stream().collect(Collectors.toMap(HarnessTool::name, x -> x));
        assertEquals("UTC+9", by.get("time_offset").execute(ctx, in("{\"city\":\"Tokyo\"}")));
        by.get("write_note").execute(ctx, in("{\"file\":\"n.txt\",\"text\":\"hi\"}"));
        assertEquals("hi", Files.readString(dir.resolve("n.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> by.get("write_note").execute(ctx, in("{\"file\":\"../x.txt\",\"text\":\"no\"}")));
        by.get("send_email").execute(ctx, in("{\"to\":\"a@b.c\",\"subject\":\"s\",\"body\":\"b\"}"));
        assertEquals(1, t.outbox().size());
        assertEquals(1, t.executions("send_email"));
        assertEquals(0, t.executions("flaky_lookup"));
        t.reset();
        assertTrue(t.outbox().isEmpty());
        assertEquals(0, t.executions("time_offset"));
    }

    @Test
    void flakyLookupFailsThenRecovers() throws Exception {
        SampleTools t = new SampleTools(2);
        HarnessTool flaky = t.all().stream().filter(x -> x.name().equals("flaky_lookup")).findFirst().orElseThrow();
        ToolContext ctx = new ToolContext("c", dir);
        assertThrows(IllegalStateException.class, () -> flaky.execute(ctx, in("{\"key\":\"k\"}")));
        assertThrows(IllegalStateException.class, () -> flaky.execute(ctx, in("{\"key\":\"k\"}")));
        assertEquals("value-of-k", flaky.execute(ctx, in("{\"key\":\"k\"}")));
        assertEquals(3, t.executions("flaky_lookup"));
    }

    @Test
    void largeOutputHasEveryFactOnceAtItsLine() throws Exception {
        LargeOutput o = new LargeOutput(2000, 5, 42);
        String[] lines = o.text().split("\n", -1);
        assertEquals(2000, lines.length);
        assertTrue(o.text().length() > 50_000, "should be large: " + o.text().length());
        int previous = -1;
        for (Fact f : o.facts()) {
            int at = o.lineOf(f);
            assertTrue(at > previous, "facts are in order");
            previous = at;
            assertTrue(lines[at].contains(f.answer()), lines[at]);
            assertEquals(1, o.text().split(Pattern.quote(f.answer()), -1).length - 1, f.answer() + " must occur once");
        }
        assertTrue(o.lineOf(o.facts().get(0)) < 400 && o.lineOf(o.facts().get(4)) > 1500, "spread across the output");
        HarnessTool tool = o.tool();
        assertEquals(ToolKind.READ_ONLY, tool.kind());
        assertEquals(o.text(), tool.execute(new ToolContext("c", dir), in("{}")));
    }

    @Test
    void largeOutputIsReproducibleAndSeedChangesAnswers() {
        assertEquals(new LargeOutput(500, 3, 7).text(), new LargeOutput(500, 3, 7).text());
        assertNotEquals(new LargeOutput(500, 3, 7).facts(), new LargeOutput(500, 3, 8).facts());
    }
}
