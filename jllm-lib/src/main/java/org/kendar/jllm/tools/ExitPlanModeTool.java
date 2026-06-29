package org.kendar.jllm.tools;

import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;
import org.kendar.jllm.session.PlanState;

import java.util.Map;

/**
 * Signals that the model has finished researching and presents a plan, leaving
 * plan mode so the session re-enables the mutating tools on the next turn. Only
 * available while plan mode is active.
 */
public class ExitPlanModeTool implements LLMTool {

  private final PlanState planState;

  public ExitPlanModeTool(PlanState planState) {
    if (planState == null) {
      throw new LLMToolException("PlanState must not be null");
    }
    this.planState = planState;
  }

  @Override
  public boolean available() {
    return planState.isActive();
  }

  @Override
  public boolean requiresApproval() {
    return true;
  }

  @Override
  public String name() {
    return "exit_plan_mode";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder(name(),
            "Call this once you have finished researching and are ready to present your "
                + "implementation plan to the user. Leaves plan mode so editing tools become "
                + "available again. Do not call this until the plan is complete.")
        .prop("plan", "string", "The final plan, in concise markdown.", true)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String plan = args == null ? null : args.get("plan");
    if (plan == null || plan.isBlank()) {
      throw new LLMToolException("Missing required argument: plan");
    }
    planState.exit();
    return "Exited plan mode. Editing tools are now available. Approved plan:\n\n" + plan;
  }
}
