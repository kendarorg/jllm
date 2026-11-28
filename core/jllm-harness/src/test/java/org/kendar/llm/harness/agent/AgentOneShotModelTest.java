package org.kendar.llm.harness.agent;

import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.plugin.OneShotModel;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentOneShotModelTest {
    private static ModelClient fake(String name, AtomicReference<String> seen) {
        return new ModelClient() {
            @Override public ModelResponse complete(ModelRequest request) { throw new UnsupportedOperationException(); }
            @Override public String simpleComplete(String system, String user, int maxTokens) {
                seen.set(system + "|" + user);
                return name;
            }
        };
    }

    private static OneShotModel.Call call(String agent) {
        return new OneShotModel.Call(agent, "Sys", "Hi", "ann", "flow: t", Set.of(), null, false, null);
    }

    @Test
    void usesAgentClientAndPromptOrTheDefault() {
        AtomicReference<String> own = new AtomicReference<>(), dflt = new AtomicReference<>();
        var writer = new AgentDefinition("writer", "w", List.of(), "Be polite.", false, fake("own", own), false, null, null);
        var m = new AgentOneShotModel(new AgentCatalog(List.of(writer)), fake("default", dflt), null, null, null);
        assertEquals("own", m.complete(call("writer")));
        assertEquals("Be polite.\n\nSys|Hi", own.get());
        assertEquals("default", m.complete(call("unknown")));
        assertEquals("Sys|Hi", dflt.get());
    }
}
