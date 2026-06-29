package org.kendar.jllm.session;

import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMRequest;
import org.kendar.jllm.base.LLMResponse;
import org.kendar.jllm.base.LLMToolCall;
import org.kendar.jllm.exceptions.LLMClientException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Test LLMClient returning queued responses (last one repeats), counting calls. */
class StubClient implements LLMClient {
  final Deque<LLMResponse> queue = new ArrayDeque<>();
  final AtomicInteger calls = new AtomicInteger();

  StubClient text(String text) {
    LLMResponse r = new LLMResponse();
    r.setResponse(text);
    queue.add(r);
    return this;
  }

  StubClient toolCall(String name, Map<String, Object> args) {
    LLMResponse r = new LLMResponse();
    r.setResponse("");
    LLMToolCall call = new LLMToolCall();
    call.setId(name + "-1");
    call.setName(name);
    call.setArguments(args);
    r.setToolCalls(List.of(call));
    queue.add(r);
    return this;
  }

  @Override
  public LLMResponse call(LLMRequest request) throws LLMClientException {
    calls.incrementAndGet();
    return queue.size() > 1 ? queue.poll() : queue.peek();
  }
}
