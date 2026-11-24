package org.kendar.llm.harness.agent;

import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.skills.SkillTool;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The tools offered to the model of a chat: the fixed ones, the chat's groups, and the user's own filter. */
final class ToolOffer {
    private final AgentLoop.Deps deps;

    ToolOffer(AgentLoop.Deps deps) {
        this.deps = deps;
    }

    /** Tools offered whatever the tool groups: the context strategy's and the harness's. */
    List<HarnessTool> fixedTools() {
        List<HarnessTool> ctx = deps.context().tools();
        if (deps.harnessTools().isEmpty()) return ctx;
        List<HarnessTool> all = new ArrayList<>(ctx);
        all.addAll(deps.harnessTools());
        return all;
    }

    /**
     * The tool set is chosen once, on the chat's first message, and stored; it only grows, through
     * request_tool_group or a user message naming a tool it lacks. Keeping it stable keeps the prompt prefix (and the
     * model's KV cache) intact.
     */
    List<HarnessTool> chatTools(String chatId, String userText) {
        var sessions = deps.sessions();
        var registry = deps.registry();
        var toolSelector = deps.toolSelector();
        if (!sessions.hasToolGroups(chatId)) {
            sessions.setToolGroups(chatId, toolSelector.selectGroups(userText, registry.all()));
        } else if (userText != null && !userText.isBlank()) {
            Set<String> named = ToolSelector.namedGroups(userText, registry.all(), sessions.toolGroups(chatId));
            if (!named.isEmpty()) sessions.addToolGroups(chatId, named);
        }
        List<HarnessTool> tools = new ArrayList<>(toolSelector.forGroups(sessions.toolGroups(chatId), registry.all()));
        tools.addAll(fixedTools());
        if (deps.requestToolGroup() != null && deps.requestToolGroup().offered(chatId)) {
            tools.add(deps.requestToolGroup());
        }
        tools.add(deps.subAgentTool());
        // the user's tool filter: applied to every tool offered to the model, including the ones added above
        UserPolicy policy = deps.identity().policyOf(chatId);
        tools.removeIf(t -> !policy.allows(t));
        // the chat's own tool set (tools switched off for this chat only)
        tools.removeIf(t -> !sessions.chatAllowsTool(chatId, t.name()));
        chatSkills(chatId, tools, policy);
        return tools;
    }

    /**
     * Restricts the skill tool to the skills of the chat's own set and of the user's policy, adds the user's own
     * skills; drops it when none is left.
     */
    void chatSkills(String chatId, List<HarnessTool> tools, UserPolicy policy) {
        var sessions = deps.sessions();
        for (int i = tools.size() - 1; i >= 0; i--) {
            if (tools.get(i) instanceof SkillTool st) {
                var view = st.forChat(skill -> policy.keepsSkill(skill) && sessions.chatAllowsSkill(chatId, skill),
                        policy.skills());
                if (view.offered().isEmpty() && (st.catalogEmpty() || !st.offered().isEmpty())) tools.remove(i);
                else tools.set(i, view);
            }
        }
    }
}
