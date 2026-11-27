package org.kendar.llm.harness.agent;

import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.plugin.OneShotModel;
import org.kendar.llm.harness.session.ChatSessionService;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The host's {@link OneShotModel}: one task for a named agent. An agent that lists tools runs in the real agent
 * loop, once, with its tools, permissions and approvals (the call can set the tools: all of the role with {@code "*"}); a call that would need an approval is not run: the task stops with {@link OneShotModel.ApprovalRequiredException}. An agent without tools (or a name that
 * is not a known agent) gets one plain completion with its model client and prompt, or the host's client.
 */
public final class AgentOneShotModel implements OneShotModel {
    private static final int MAX_TOKENS = 4096;
    /** Title of the chat of a call that names none. */
    static final String TASK_CHAT_TITLE = "flow tasks";

    private final AgentCatalog agents;
    private final ModelClient model;
    private final ModelClient fast;
    private final Supplier<SubAgentRunner> loop;
    private final ChatSessionService sessions;
    private final Map<String, String> chats = new ConcurrentHashMap<>();

    /** @param loop the agent loop; null or returning null runs every agent as a plain completion */
    public AgentOneShotModel(AgentCatalog agents, ModelClient model, ModelClient fast, Supplier<SubAgentRunner> loop,
                             ChatSessionService sessions) {
        this.agents = agents;
        this.model = model;
        this.fast = fast == null ? model : fast;
        this.loop = loop;
        this.sessions = sessions;
    }

    /** @param fast the cheap model of the host; null = the main model */
    @Override
    public String classify(String system, String user) {
        return fast.simpleComplete(system, user, 16, 0.0, null);
    }

    @Override
    public String complete(Call call) {
        String agent = call.agent();
        String system = call.system();
        String user = call.user();
        AgentDefinition def = agent == null || agent.isBlank() ? null : agents.get(agent);
        SubAgentRunner l = loop == null ? null : loop.get();
        java.util.List<String> tools = call.tools();
        boolean agentic = tools != null ? !tools.isEmpty() : def != null && !def.tools().isEmpty();
        if (agentic && l != null && sessions != null) {
            if (tools != null) { // the task chooses its tools; the user's role still limits them in the loop
                AgentDefinition base = def != null ? def : agents.get(AgentCatalog.DEFAULT);
                def = new AgentDefinition(base.name(), base.description(),
                        tools.contains("*") ? java.util.List.of() : tools, base.prompt(), base.inheritContext(),
                        base.model(), base.readOnly(), base.temperature(), base.thinking());
            }
            String prompt = system == null || system.isBlank() ? user : system + "\n\n" + user;
            Set<String> approved = call.approved() == null ? Set.of() : call.approved();
            return l.runAgentTask(chat(call.principal(), call.title()), prompt, def, call.unattended(), approved,
                    call.onDenied());
        }
        ModelClient client = def != null && def.model() != null ? def.model() : model;
        String prompt = def == null || def.prompt() == null || def.prompt().isBlank() ? ""
                : def.prompt() + (system == null || system.isBlank() ? "" : "\n\n");
        Double temperature = def == null ? null : def.temperature();
        return client.simpleComplete(prompt + (system == null ? "" : system), user, MAX_TOKENS, temperature, null);
    }

    /**
     * The chat of one flow run: it belongs to the user the run is for and shows in their chat list, so they can see
     * what the tasks did. Calls with the same principal and title share it.
     */
    private String chat(String principal, String title) {
        String name = title == null || title.isBlank() ? TASK_CHAT_TITLE : title;
        return chats.computeIfAbsent((principal == null ? "" : principal) + "\u0000" + name,
                k -> sessions.create(name, principal).chatId());
    }
}
