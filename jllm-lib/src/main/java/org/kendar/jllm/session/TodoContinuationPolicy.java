package org.kendar.jllm.session;

import org.kendar.jllm.base.LLMClassifier;
import org.kendar.jllm.commons.LLMContext;
import org.kendar.jllm.exceptions.LLMClassificationException;
import org.kendar.jllm.tools.TodoStore;

/**
 * Hybrid continuation policy:
 * <ol>
 *   <li>Deterministic primary gate: continue while the {@link TodoStore} still
 *       holds incomplete items (no extra LLM call).</li>
 *   <li>Classifier fallback: when no todos remain but the last message trails
 *       off announcing more work, an optional {@code continuationClassifier}
 *       decides {@code continue} vs {@code done}.</li>
 * </ol>
 * Any of {@code todoStore}, {@code classifier} and {@code context} may be null.
 */
public class TodoContinuationPolicy implements ContinuationPolicy {

  private final TodoStore todoStore;
  private final LLMClassifier classifier;
  private final LLMContext context;

  public TodoContinuationPolicy(TodoStore todoStore, LLMClassifier classifier, LLMContext context) {
    this.todoStore = todoStore;
    this.classifier = classifier;
    this.context = context;
  }

  @Override
  public boolean shouldContinue(String lastAssistantText) {
    if (todoStore != null && todoStore.hasIncomplete()) {
      return true;
    }
    if (classifier != null && context != null) {
      String text = lastAssistantText == null ? "" : lastAssistantText;
      try {
        return "continue".equals(classifier.classify(context, classifier.buildPrompt() + text));
      } catch (LLMClassificationException e) {
        // Classifier could not decide -> err on the side of stopping.
        return false;
      }
    }
    return false;
  }
}
