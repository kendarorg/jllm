package org.kendar.jllm.session;

/**
 * Decides whether the {@link AgenticLoop} should keep going after a model turn
 * that produced no tool call. Lets the loop recover from weak models that
 * narrate a next step (e.g. "Now let me create the model class:") without
 * actually firing the tool, which would otherwise end the turn prematurely.
 */
@FunctionalInterface
public interface ContinuationPolicy {

  /**
   * @param lastAssistantText the text of the model turn that contained no tool call
   * @return {@code true} to nudge the model and continue the loop, {@code false} to stop
   */
  boolean shouldContinue(String lastAssistantText);
}
