package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.commands.CustomCommand;
import org.kendar.llm.harness.hooks.Hook;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.plugin.MemoryPluginStorage;
import org.kendar.llm.harness.plugin.PluginRegistrar;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.tools.DocumentReader;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.workflows.api.WorkflowApi;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowsPluginTest {
    @TempDir
    Path dir;

    /** Collects what a plugin contributes. */
    static final class Recorder implements PluginRegistrar {
        final Map<String, CustomCommand> commands = new LinkedHashMap<>();
        final java.util.List<HarnessTool> tools = new java.util.ArrayList<>();
        final ServiceRegistry services = new ServiceRegistry();

        public PluginRegistrar tool(HarnessTool t) {
            tools.add(t);
            return this;
        }

        public PluginRegistrar hook(HookEvent e, Hook h) {
            return this;
        }

        public PluginRegistrar command(String n, String d, CustomCommand c) {
            commands.put(n, c);
            return this;
        }

        public PluginRegistrar documentReader(DocumentReader r) {
            return this;
        }

        public PluginRegistrar replaces(String t) {
            return this;
        }

        public PluginRegistrar toolNameCheck(Consumer<Collection<String>> c) {
            return this;
        }

        public ServiceRegistry services() {
            return services;
        }

        public org.kendar.llm.harness.tools.ToolCatalog catalog() {
            return null;
        }
    }

    private static JsonNode j(String s) {
        return Json.parse(s.replace('\'', '"'));
    }

    private JsonNode settings() {
        return j("{'dir':'" + dir.toString().replace("\\", "/") + "','chatUser':'ann'}");
    }

    @Test
    void installsCommandsAndServiceButNoTools() throws Exception {
        Recorder r = new Recorder();
        new WorkflowsPlugin().install(r, settings());
        assertTrue(r.tools.isEmpty(), "flows are never tools");
        assertEquals(Set.of("/flows", "/flow-start", "/flow-resume", "/flow-request", "/flow-cancel"), r.commands.keySet());
        WorkflowApi api = r.services.lookup(WorkflowApi.class).orElseThrow();

        api.save("ann", Set.of(), "personal", j("""
                {'name':'ask','start':'q','steps':{
                 'q':{'type':'ask','kind':'text','text':'Name?','next':'end'},
                 'end':{'type':'finish','result':{'n':'{{steps.q}}'}}}}"""));
        assertTrue(r.commands.get("/flows").run("c", "", java.util.List.of()).contains("personal/ask"));
        String started = r.commands.get("/flow-start").run("c", "ask", java.util.List.of());
        assertTrue(started.contains("waiting") && started.contains("Name?"), started);
        String runId = started.substring(4, started.indexOf(':'));
        String done = r.commands.get("/flow-resume").run("c", runId + " Bob", java.util.List.of());
        assertTrue(done.contains("completed") && done.contains("Bob"), done);
        assertTrue(r.commands.get("/flow-resume").run("c", "", java.util.List.of()).startsWith("Usage"));
        api.save("ann", Set.of(), "personal", j("""
                {'name':'echo','start':'end','steps':{'end':{'type':'finish','result':{'c':'{{input.chat}}'}}}}"""));
        var hist = java.util.List.of(new org.kendar.llm.harness.commands.CustomCommand.ChatLine("user", "hello there"));
        assertTrue(r.commands.get("/flow-start").run("c", "--chat echo", hist).contains("hello there"));
        assertTrue(r.commands.get("/flow-start").run("c", "echo", hist).contains("no value"), "chat only on request");
    }

    @Test
    void usesHostPluginStorageWhenPublished() {
        Recorder r = new Recorder();
        MemoryPluginStorage mem = new MemoryPluginStorage();
        r.services.publish(PluginStorages.class, id -> mem);
        new WorkflowsPlugin().install(r, settings());
        WorkflowApi api = r.services.lookup(WorkflowApi.class).orElseThrow();
        api.save("ann", Set.of(), "personal", j("{'name':'x','start':'e','steps':{'e':{'type':'finish'}}}"));
        assertEquals(java.util.List.of("x"), mem.ids("ann", "flows"));
    }

    @Test
    void secondPluginInstanceIsRefusedAsDuplicateService() {
        Recorder r = new Recorder();
        new WorkflowsPlugin().install(r, settings());
        assertThrows(IllegalStateException.class, () -> new WorkflowsPlugin().install(r, settings()));
    }
}
