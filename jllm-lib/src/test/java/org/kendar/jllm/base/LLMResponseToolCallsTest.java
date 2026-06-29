package org.kendar.jllm.base;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LLMResponseToolCallsTest {

  @Test
  void parsesToolCalls() throws Exception {
    String body = "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":"
        + "[{\"function\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}}]},\"done\":true}";

    LLMResponse response = LLMObjectMapper.getObjectMapper().readValue(body, LLMResponse.class);

    assertEquals(1, response.getToolCalls().size());
    LLMToolCall call = response.getToolCalls().get(0);
    assertEquals("read_file", call.getName());
    assertEquals("a.txt", call.getArguments().get("path"));
  }

  @Test
  void plainResponseHasNoToolCalls() throws Exception {
    String body = "{\"message\":{\"role\":\"assistant\",\"content\":\"Hello world\"},\"done\":true}";

    LLMResponse response = LLMObjectMapper.getObjectMapper().readValue(body, LLMResponse.class);

    assertTrue(response.getToolCalls().isEmpty());
    assertEquals("Hello world", response.getResponse());
  }
}
