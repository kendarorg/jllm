package org.kendar.llm.harness.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.session.SubAgentRunLog;

/** Builds the context strategy of a harness from its components (builder code configuration). */
@FunctionalInterface
public interface ContextStrategyFactory {

    /**
     * @param model        the main model
     * @param summaryModel model for the strategy's own summary requests: the fast model if configured, else the main
     */
    record Deps(HarnessConfig config, ModelClient model, ModelClient summaryModel, ApprovalService approvals,
                ObjectMapper mapper, HarnessMetrics metrics, SubAgentRunLog subAgentRuns) {
    }

    ContextStrategy create(Deps deps);
}
