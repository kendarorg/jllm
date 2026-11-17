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
        return new OneShotModel.Call(agent, "Sys", "Hi", "ann", "flow: t", Set.of(), null);
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

    /** A client that records the user of the call scope it runs in. */
    private static ModelClient scopeReader(AtomicReference<String> user) {
        return new ModelClient() {
            @Override public ModelResponse complete(ModelRequest request) { throw new UnsupportedOperationException(); }
            @Override public String simpleComplete(String system, String u, int maxTokens) {
                var c = org.kendar.llm.harness.auth.CallerScope.current();
                user.set(c == null ? "none" : c.principal() == null ? "local" : c.principal().id());
                return "ok";
            }
        };
    }

    @Test
    void aFlowCallAndItsClassifierRunForTheUserOfTheRun() throws Exception {
        AtomicReference<String> main = new AtomicReference<>(), fast = new AtomicReference<>();
        var m = new AgentOneShotModel(new AgentCatalog(List.of()), scopeReader(main), scopeReader(fast), null, null);
        // user B is active in another scope on this thread: the call still runs for the user of the run
        org.kendar.llm.harness.auth.CallerScope.runAs(org.kendar.llm.harness.auth.HarnessPrincipal.of("bob"), () -> {
            m.complete(call("unknown")); // principal "ann"
            try {
                m.classify("ann", "s", "u");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertEquals("ann", main.get());
        assertEquals("ann", fast.get());
        assertEquals("bob", runAs("bob", m));
    }

    private static String runAs(String user, AgentOneShotModel m) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        var probe = new AgentOneShotModel(new AgentCatalog(List.of()), scopeReader(seen), null, null, null);
        probe.classify(user, "s", "u");
        return seen.get();
    }
}
