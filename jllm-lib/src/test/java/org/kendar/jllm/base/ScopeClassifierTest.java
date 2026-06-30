package org.kendar.jllm.base;

import org.junit.jupiter.api.Test;
import org.kendar.jllm.LLMSettings;
import org.kendar.jllm.commons.LLMContext;
import org.kendar.jllm.exceptions.LLMClassificationException;
import org.kendar.jllm.exceptions.LLMClientException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour of the default {@code scopeClassifier} loaded from
 * {@code default/.jllm}: classify() success/retries/max-retries/failure and the
 * generated prompt.
 */
class ScopeClassifierTest {

  /** Mock client returning a queued response per call (last one repeats), counting calls. */
  private static class QueueClient implements LLMClient {
    final Deque<LLMResponse> queue = new ArrayDeque<>();
    final AtomicInteger calls = new AtomicInteger();

    QueueClient response(String response, String thinking) {
      var r = new LLMResponse();
      r.setResponse(response);
      r.setThinking(thinking);
      queue.add(r);
      return this;
    }

    @Override
    public LLMResponse call(LLMRequest request) throws LLMClientException {
      calls.incrementAndGet();
      return queue.size() > 1 ? queue.poll() : queue.peek();
    }
  }

  /**
   * Walk up from the test working dir to find the setting dir holding the real
   * {@code .jllm/classifiers/default} (the dir itself or a {@code default} sub-dir).
   */
  private static Path settingDirWithDefaultJllm() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null) {
      for (Path candidate : new Path[]{dir, dir.resolve("default")}) {
        if (Files.isDirectory(candidate.resolve(".jllm").resolve("classifiers").resolve("default"))) {
          return candidate;
        }
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("No .jllm/classifiers/default found above " + System.getProperty("user.dir"));
  }

  private LLMConfigClassifier loadDefaultScopeClassifier() {
    LLMSettings settings = new LLMSettings();
    settings.setSettingDirs(List.of(settingDirWithDefaultJllm().toString()));
    LLMConfigManager.initialize(settings);
    return LLMConfigManager.get("scopeClassifier", LLMConfigClassifier.class);
  }

  private LLMContext contextWith(LLMClient client) {
    var ctx = new LLMContext();
    ctx.setClient(client);
    return ctx;
  }

  @Test
  void classifySucceedsOnFirstTry() {
    LLMConfigClassifier scope = loadDefaultScopeClassifier();
    QueueClient client = new QueueClient().response("plan", "");

    String result = scope.classify(contextWith(client), "draft me a plan");

    assertEquals("plan", result);
    assertEquals(1, client.calls.get(), "should not retry on success");
  }

  @Test
  void classifyMatchesFromThinkingField() {
    LLMConfigClassifier scope = loadDefaultScopeClassifier();
    // response is non-matching, but thinking carries the classification.
    QueueClient client = new QueueClient().response("nonsense", "research");

    String result = scope.classify(contextWith(client), "look into this");

    assertEquals("research", result);
    assertEquals(1, client.calls.get());
  }

  @Test
  void classifySucceedsAfterRetries() {
    LLMConfigClassifier scope = loadDefaultScopeClassifier();
    QueueClient client = new QueueClient()
        .response("dunno", "")   // attempt 1: no match
        .response("maybe", "")   // attempt 2: no match
        .response("code", "");   // attempt 3: match

    String result = scope.classify(contextWith(client), "write a function");

    assertEquals("code", result);
    assertEquals(3, client.calls.get());
  }

  @Test
  void classifyFailsAfterMaxRetries() {
    LLMConfigClassifier scope = loadDefaultScopeClassifier();
    // never matches -> exhausts the 3 configured retries then throws.
    QueueClient client = new QueueClient().response("never matches", "still nothing");

    assertThrows(LLMClassificationException.class,
        () -> scope.classify(contextWith(client), "anything"));
    assertEquals(scope.getRetries(), client.calls.get(), "should try exactly maxRetries times");
  }

  @Test
  void classifyToleratesNullThinkingField() {
    LLMConfigClassifier scope = loadDefaultScopeClassifier();
    // Model (e.g. Qwen) returns no thinking block at all -> getThinking() is null.
    // Previously isClassified(null) threw NPE on result.trim(); now it must just
    // not match and let the retry/match logic carry on.
    QueueClient client = new QueueClient()
        .response("nonsense", null)  // attempt 1: response no match, thinking null
        .response("code", null);     // attempt 2: match

    String result = scope.classify(contextWith(client), "write a function");

    assertEquals("code", result);
    assertEquals(2, client.calls.get());
  }

  @Test
  void classifyMatchesFromThinkingWhenResponseNull() {
    LLMConfigClassifier scope = loadDefaultScopeClassifier();
    // Response missing entirely, classification carried only by thinking.
    QueueClient client = new QueueClient().response(null, "research");

    String result = scope.classify(contextWith(client), "look into this");

    assertEquals("research", result);
    assertEquals(1, client.calls.get());
  }

  @Test
  void buildPromptContainsDescriptionValuesAndAllowedWords() {
    LLMConfigClassifier scope = loadDefaultScopeClassifier();

    String prompt = scope.buildPrompt();

    // description header
    assertTrue(prompt.contains("Classify the scope of the prompt"), prompt);
    // every key=value pair from the classification map
    scope.getClassification().forEach((k, v) ->
        assertTrue(prompt.contains(k + ": " + v), "missing pair " + k + ": " + v + " in:\n" + prompt));
    // one-word instruction with the allowed words joined by " or "
    assertTrue(prompt.contains("Reply with exactly one word:"), prompt);
    assertTrue(prompt.contains(String.join(" or ", scope.getClassification().keySet())), prompt);
    assertTrue(prompt.contains("Classify the following text:"), prompt);
  }
}
