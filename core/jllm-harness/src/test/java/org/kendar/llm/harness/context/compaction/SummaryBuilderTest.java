package org.kendar.llm.harness.context.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummaryBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ArrayNode spanWithSummary(String body) {
        ArrayNode a = MAPPER.createArrayNode();
        a.addObject().put("role", "user").putArray("content").addObject().put("type", "text")
                .put("text", SummaryBuilder.SUMMARY_PREFIX + body);
        return a;
    }

    @Test
    void bulletsAndNormalisation() {
        assertEquals(List.of("- a", "* b", "1. c"), SummaryBuilder.bullets("head\n- a\ntext\n* b\n1. c"));
        assertEquals(List.of(), SummaryBuilder.bullets(null));
        assertEquals("the reviewer is sam lee", SummaryBuilder.normalised("- The **reviewer** is `Sam`  Lee."));
        assertEquals("plain", SummaryBuilder.normalised("Plain;"));
    }

    @Test
    void labels() {
        assertEquals("reviewer", SummaryBuilder.label("the reviewer is sam"));
        assertEquals("port", SummaryBuilder.label("port = 80"));
        assertEquals("name", SummaryBuilder.label("name: x"));
        assertNull(SummaryBuilder.label("no label here"));
        assertNull(SummaryBuilder.label("one two three four five six seven eight nine: x"), "too many words");
    }

    @Test
    void lostFactsSkipsRepeatedAndReplaced() {
        String prior = "## User-Stated Facts\n- Reviewer is Priya\n- Port = 80\n- Deadline: Friday";
        String updated = "## User-Stated Facts\n- reviewer is Sam\n- port = 80.";
        assertEquals(List.of("- Deadline: Friday"), SummaryBuilder.lostFacts(prior, updated));
        assertEquals(List.of(), SummaryBuilder.lostFacts(null, updated));
        assertEquals(3, SummaryBuilder.lostFacts(prior, null).size());
    }

    @Test
    void sectionAndHeading() {
        String t = "## A\none\n\n## B\ntwo\n## C\nthree";
        assertEquals("## B\ntwo", SummaryBuilder.section(t, "## B"));
        assertNull(SummaryBuilder.section(t, "## Z"));
        assertTrue(SummaryBuilder.hasHeading(t, "## C"));
        assertFalse(SummaryBuilder.hasHeading(t, "## D"));
    }

    @Test
    void appendToSectionGoesBeforeTrailingBlankLinesAndNextHeading() {
        String t = "## A\n- x\n\n## B\n- y";
        assertEquals("## A\n- x\n- n\n\n## B\n- y", SummaryBuilder.appendToSection(t, "## A", List.of("- n")));
        assertEquals("## A\n- x\n\n## B\n- y\n- n", SummaryBuilder.appendToSection(t, "## B", List.of("- n")));
    }

    @Test
    void keptSectionsWithoutPriorSummaryIsIdentity() {
        SummaryBuilder.Kept k = SummaryBuilder.keptSections("## Objective\nx", MAPPER.createArrayNode(), 1000);
        assertEquals("## Objective\nx", k.summary());
        assertTrue(k.droppedFacts().isEmpty());
    }

    @Test
    void keptSectionsRestoresMissingHeadingInFront() {
        ArrayNode span = spanWithSummary("## Objective\ngoal\n\n## Work State\nws");
        SummaryBuilder.Kept k = SummaryBuilder.keptSections("## Work State\nnew", span, 10_000);
        assertEquals("## Objective\ngoal\n\n## Work State\nnew", k.summary());
    }

    @Test
    void keptSectionsRestoresLostFactsWithinMaxChars() {
        ArrayNode span = spanWithSummary("## User-Stated Facts\n- aaaa: 1\n- bbbb: 2");
        String updated = "## User-Stated Facts\n- cccc: 3";
        SummaryBuilder.Kept all = SummaryBuilder.keptSections(updated, span, 10_000);
        assertEquals("## User-Stated Facts\n- cccc: 3\n- aaaa: 1\n- bbbb: 2", all.summary());
        SummaryBuilder.Kept some = SummaryBuilder.keptSections(updated, span, updated.length() + "- aaaa: 1".length() + 1);
        assertEquals(List.of("- bbbb: 2"), some.droppedFacts());
        assertTrue(some.summary().contains("- aaaa: 1"));
    }

    @Test
    void renderLevels() {
        ArrayNode m = MAPPER.createArrayNode();
        m.addObject().put("role", "user").put("content", "hello");
        var a = m.addObject().put("role", "assistant").putArray("content");
        a.addObject().put("type", "thinking").put("thinking", "t");
        a.addObject().put("type", "text").put("text", "ok");
        a.addObject().put("type", "tool_use").put("name", "grep").putObject("input").put("q", "z");
        m.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                .put("tool_use_id", "1").put("content", "r".repeat(2000));
        String l0 = SummaryBuilder.render(m);
        assertTrue(l0.startsWith("USER: hello\nASSISTANT: \nok\n[tool_use grep {\"q\":\"z\"}]\n"), l0);
        assertTrue(l0.contains(" … "), "long result clipped");
        assertTrue(SummaryBuilder.render(m, 2).contains("[tool_result omitted]"));
        assertEquals("USER: hello\n", SummaryBuilder.render(m, 3));
    }

    @Test
    void shortenKeepsPriorSummaryWholeAndCutsOnLineBoundary() {
        ArrayNode span = spanWithSummary("prior");
        for (int i = 0; i < 20; i++) span.addObject().put("role", "user").put("content", "line number " + i);
        String head = SummaryBuilder.render(spanWithSummary("prior"), 0);
        String out = SummaryBuilder.shorten(span, 0, head.length() + 60);
        assertTrue(out.startsWith(head + "[earlier history omitted]\n"));
        assertTrue(out.endsWith("line number 19\n"));
        assertTrue(out.substring(head.length()).split("\n")[1].startsWith("USER: "), "no half line");
        assertNull(SummaryBuilder.shorten(span, 0, head.length()), "prior summary alone does not fit");
        assertEquals(SummaryBuilder.render(span, 0), SummaryBuilder.shorten(span, 0, 1_000_000));
    }
}
