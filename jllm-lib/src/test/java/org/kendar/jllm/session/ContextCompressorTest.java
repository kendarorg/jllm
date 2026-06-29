package org.kendar.jllm.session;

import org.junit.jupiter.api.Test;
import org.kendar.jllm.base.LLMMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompressorTest {

  private static LLMMessage msg(String role, String content) {
    return new LLMMessage(role, content);
  }

  @Test
  void doesNotCompressShortHistories() {
    StubClient client = new StubClient().text("summary");
    // Tiny window but the history is shorter than the kept-recent window.
    ContextCompressor compressor = new ContextCompressor(client, 100, 0.5, 6);

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(msg("system", "sys"));
    messages.add(msg("user", "hi"));

    assertSame(messages, compressor.compress(messages));
    assertEquals(0, client.calls.get());
  }

  @Test
  void compressesOldTurnsKeepingSystemAndRecent() {
    StubClient client = new StubClient().text("SUMMARY");
    // Trigger easily: window of 50 tokens, compress at 50%.
    ContextCompressor compressor = new ContextCompressor(client, 50, 0.5, 2);

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(msg("system", "the system prompt"));
    for (int i = 0; i < 10; i++) {
      messages.add(msg("user", "a fairly long user message number " + i + " padding padding"));
      messages.add(msg("assistant", "a fairly long assistant reply number " + i + " padding"));
    }

    assertTrue(compressor.shouldCompress(messages));
    List<LLMMessage> out = compressor.compress(messages);

    assertEquals("system", out.get(0).getRole());
    assertEquals("the system prompt", out.get(0).getContent());
    assertEquals("system", out.get(1).getRole());
    assertTrue(out.get(1).getContent().contains("SUMMARY"), out.get(1).getContent());
    // System prompt + summary + 2 recent kept messages.
    assertEquals(4, out.size());
    assertSame(messages.get(messages.size() - 1), out.get(out.size() - 1));
    assertEquals(1, client.calls.get());
  }

  @Test
  void fallsBackToStructuralDigestOnClientFailure() {
    // Empty queue -> StubClient.peek returns null -> NPE path; use a failing client instead.
    ContextCompressor compressor = new ContextCompressor(req -> {
      throw new org.kendar.jllm.exceptions.LLMClientException("boom", null);
    }, 50, 0.5, 2);

    List<LLMMessage> messages = new ArrayList<>();
    messages.add(msg("system", "sys"));
    for (int i = 0; i < 10; i++) {
      messages.add(msg("user", "message with enough content to exceed the tiny window " + i));
      messages.add(msg("assistant", "reply with enough content to exceed the tiny window " + i));
    }

    List<LLMMessage> out = compressor.compress(messages);
    assertTrue(out.get(1).getContent().contains("earlier messages omitted"), out.get(1).getContent());
  }
}
