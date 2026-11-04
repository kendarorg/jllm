package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PartialToolCallTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jsonFieldReadsUnterminatedStringAndDropsDanglingEscape() {
        String raw = "{\"path\": \"src/A.kt\", \"content\": \"line \\\"1\\\"\\n\\tline 2\\u00e9\\";
        assertEquals("src/A.kt", PartialToolCall.jsonField(raw, "path"));
        assertEquals("line \"1\"\n\tline 2é", PartialToolCall.jsonField(raw, "content"));
        assertNull(PartialToolCall.jsonField(raw, "missing"));
    }

    @Test
    void jsonFieldStopsAtIncompleteUnicodeEscape() {
        assertEquals("ab", PartialToolCall.jsonField("{\"content\": \"ab\\u00", "content"));
    }

    @Test
    void xmlFieldReadsClosedAndUnclosedParameters() {
        String raw = "<tool_call>\n<function=write_file>\n<parameter=path>\na.txt\n</parameter>\n"
                + "<parameter=content>\nx\ny";
        assertEquals("a.txt", PartialToolCall.xmlField(raw, "path"));
        assertEquals("x\ny", PartialToolCall.xmlField(raw, "content"));
        assertEquals("write_file", PartialToolCall.textCallName(raw));
    }

    @Test
    void fieldPrefersParsedInput() {
        ObjectNode in = mapper.createObjectNode().put("path", "p");
        in.put(PartialToolCall.RAW, "{\"path\": \"other\"");
        assertEquals("p", PartialToolCall.field(in, "path"));
        assertNull(PartialToolCall.field(mapper.createObjectNode(), "path"));
    }

    @Test
    void openTextCallNeedsAnUnclosedCall() {
        assertEquals(3, PartialToolCall.openTextCall("hi <tool_call>{\"name\""));
        assertEquals(-1, PartialToolCall.openTextCall("<tool_call>{}</tool_call>"));
        assertEquals(-1, PartialToolCall.openTextCall("plain"));
    }
}
