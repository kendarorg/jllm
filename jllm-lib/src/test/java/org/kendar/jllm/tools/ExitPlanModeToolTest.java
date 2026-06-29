package org.kendar.jllm.tools;

import org.junit.jupiter.api.Test;
import org.kendar.jllm.exceptions.LLMToolException;
import org.kendar.jllm.session.PlanState;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExitPlanModeToolTest {

  @Test
  void availableOnlyWhilePlanModeActive() {
    PlanState state = new PlanState(true);
    ExitPlanModeTool tool = new ExitPlanModeTool(state);
    assertTrue(tool.available());
    tool.act(Map.of("plan", "do the thing"));
    assertFalse(state.isActive());
    assertFalse(tool.available());
  }

  @Test
  void requiresPlanArgument() {
    ExitPlanModeTool tool = new ExitPlanModeTool(new PlanState(true));
    assertThrows(LLMToolException.class, () -> tool.act(Map.of()));
  }
}
