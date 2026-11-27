package org.kendar.llm.harness;

import org.kendar.llm.harness.agent.AgentService;
import org.kendar.llm.harness.context.ContextStrategy;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.integration.ExternalSources;
import org.kendar.llm.harness.llm.ModelSwitch;
import org.kendar.llm.harness.llm.ProviderCatalog;
import org.kendar.llm.harness.permission.PermissionRules;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.safety.ContentWarnings;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.harness.tools.Checkpoints;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.tools.StorageQuota;

/** The assembled components {@link AgentHarnessBuilder#build()} hands to the {@link AgentHarness} constructor. */
record HarnessParts(AgentService service, ChatSessionService sessions, HarnessToolRegistry registry,
                    ExternalSources sources, SkillCatalog skills, SandboxExecutor sandbox,
                    SubAgentRunLog subAgentRuns, ContextStrategy context, HarnessConfig config,
                    HarnessPricing pricing, PermissionRules permissionRules, ContentWarnings contentWarnings,
                    ServiceRegistry services, ProviderCatalog providers, ModelSwitch modelSwitch, int maxParallel,
                    Checkpoints checkpoints, StorageQuota quota, HarnessAuth auth, Hooks hooks) {
}
