package org.kendar.llm.workflowweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.commands.CustomCommand;
import org.kendar.llm.harness.hooks.Hook;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.plugin.MemoryPluginStorage;
import org.kendar.llm.harness.plugin.PluginRegistrar;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.tools.DocumentReader;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.plugin.WebPluginHost;
import org.kendar.llm.workflows.WorkflowsPlugin;
import org.kendar.llm.harness.plugin.WebRequest;
import org.kendar.llm.harness.plugin.WebResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The refund-style flow driven only through the web endpoints, on the host's plugin storage. */
class RefundThroughEndpointsTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode j(String s) throws Exception {
        return M.readTree(s.replace('\'', '"'));
    }

    private static final String FLOW = """
            {'name':'refund','scope':'global','roles':[],'start':'review','steps':{
              'review':{'type':'decide','by':'user','question':'Approve the refund?','who':'operator',
                        'outcomes':{'approve':'how','reject':'no'}},
              'how':{'type':'ask','kind':'text','text':'Reply for order {{input.order}}','who':'customer','next':'end'},
              'no':{'type':'finish','result':{'r':'rejected'}},
              'end':{'type':'finish','result':{'r':'approved','reply':'{{steps.how}}'}}}}""";

    private static final class Rec implements PluginRegistrar {
        final ServiceRegistry services = new ServiceRegistry();

        public PluginRegistrar tool(HarnessTool t) {
            return this;
        }

        public PluginRegistrar hook(HookEvent e, Hook h) {
            return this;
        }

        public PluginRegistrar command(String n, String d, CustomCommand c) {
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

    private WebResponse call(WebPluginHost h, String method, String path, String user, boolean admin, String body)
            throws Exception {
        return h.dispatch(WorkflowWebPlugin.ID, new WebRequest(method, path, Map.of(), Map.of(),
                body == null ? null : j(body), user, admin, admin ? Set.of("admin") : Set.of()));
    }

    @Test
    void refundRunsThroughEndpointsAndEachUserSeesOnlyOwnRuns() throws Exception {
        Rec rec = new Rec();
        MemoryPluginStorage mem = new MemoryPluginStorage();
        rec.services.publish(PluginStorages.class, id -> mem);
        new WorkflowsPlugin().install(rec, j("{'dir':'" + Path.of(System.getProperty("java.io.tmpdir"))
                .toString().replace("\\", "/") + "'}"));

        WebPluginHost h = new WebPluginHost(rec.services, id -> mem, j("{'jllm-workflow-web':{'enabled':true}}"));
        h.load(List.of(new WorkflowWebPlugin()));
        assertEquals(List.of("jllm-workflow-web"), h.loaded());
        List<String> events = new ArrayList<>();
        h.events(WorkflowWebPlugin.ID).subscribe("ann", e -> events.add(e.type() + ":" + e.data()));

        // only an administrator publishes a global flow
        assertEquals(403, call(h, "PUT", "/flows/global", "ann", false, FLOW).status());
        assertEquals(200, call(h, "PUT", "/flows/global", "root", true, FLOW).status());
        assertEquals("order", call(h, "GET", "/flows/global/refund/inputs", "ann", false, null).body().get(0).asText());
        assertEquals(1, call(h, "GET", "/flows", "ann", false, null).body().size());

        // the operator's single select
        JsonNode run = call(h, "POST", "/runs", "ann", false, "{'name':'refund','input':{'order':'A7'}}").body();
        String id = run.path("runId").asText();
        run = settle(h, id);
        assertEquals("waiting", run.path("status").asText());
        assertEquals("SINGLE", run.path("pending").path("kind").asText());
        assertEquals("operator", run.path("pending").path("who").asText());
        assertEquals(1, call(h, "GET", "/waiting/count", "ann", false, null).body().path("count").asInt());
        assertEquals(400, call(h, "POST", "/runs/" + id + "/answer", "ann", false, "{'answer':'maybe'}").status(),
                "an answer outside the options is refused");

        // the customer's free text
        call(h, "POST", "/runs/" + id + "/answer", "ann", false, "{'answer':'approve'}");
        run = settle(h, id);
        assertEquals("TEXT", run.path("pending").path("kind").asText());
        assertEquals("customer", run.path("pending").path("who").asText());
        call(h, "POST", "/runs/" + id + "/answer", "ann", false, "{'answer':'Thanks, send it by post'}");
        run = settle(h, id);
        assertEquals("completed", run.path("status").asText());
        assertEquals("Thanks, send it by post", run.path("result").path("reply").asText());
        assertEquals(0, call(h, "GET", "/waiting/count", "ann", false, null).body().path("count").asInt());
        assertFalse(call(h, "GET", "/runs/" + id + "/history", "ann", false, null).body().isEmpty());

        // another user cannot see the run: it looks like it does not exist
        assertEquals(404, call(h, "GET", "/runs/" + id, "bob", false, null).status());
        assertEquals(0, call(h, "GET", "/runs", "bob", false, null).body().size());
        assertEquals(1, call(h, "GET", "/runs", "ann", false, null).body().size());
        assertTrue(events.stream().anyMatch(e -> e.startsWith("waiting:")), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.contains("completed")), events.toString());

        // cancelling a waiting run
        JsonNode second = call(h, "POST", "/runs", "ann", false, "{'name':'refund','input':{'order':'B1'}}").body();
        settle(h, second.path("runId").asText());
        assertEquals("cancelled", call(h, "POST", "/runs/" + second.path("runId").asText() + "/cancel", "ann", false,
                null).body().path("status").asText());
    }

    /** Runs are walked in the background: waits until the run is no longer pending or running. */
    private JsonNode settle(WebPluginHost h, String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode run = call(h, "GET", "/runs/" + id, "ann", false, null).body();
            String st = run.path("status").asText();
            if (!st.equals("pending") && !st.equals("running")) {
                Thread.sleep(50); // the end event follows the last save
                return call(h, "GET", "/runs/" + id, "ann", false, null).body();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("run " + id + " did not settle");
    }

    @Test
    void editorEndpointsAndAdminSeesEveryGlobalFlow() throws Exception {
        Rec rec = new Rec();
        MemoryPluginStorage mem = new MemoryPluginStorage();
        rec.services.publish(PluginStorages.class, id -> mem);
        new WorkflowsPlugin().install(rec, j("{'dir':'" + Path.of(System.getProperty("java.io.tmpdir"))
                .toString().replace("\\", "/") + "'}"));
        WebPluginHost h = new WebPluginHost(rec.services, id -> mem, j("{'jllm-workflow-web':{'enabled':true}}"));
        h.load(List.of(new WorkflowWebPlugin()));

        String restricted = FLOW.replace("'name':'refund'", "'name':'fin'").replace("'roles':[]", "'roles':['finance']");
        assertEquals(200, call(h, "PUT", "/flows/global", "root", true, restricted).status());
        assertEquals(200, call(h, "PUT", "/flows/personal", "ann", false,
                "{'name':'mine','start':'e','steps':{'e':{'type':'finish'}}}").status());

        // ann does not see the restricted flow; the administrator sees it, marked as not shared with them
        JsonNode annList = call(h, "GET", "/flows", "ann", false, null).body();
        assertEquals(1, annList.size());
        assertEquals("mine", annList.get(0).path("name").asText());
        assertTrue(annList.get(0).path("canEdit").asBoolean());
        JsonNode rootList = call(h, "GET", "/flows", "root", true, null).body();
        assertEquals(1, rootList.size());
        assertEquals("fin", rootList.get(0).path("name").asText());
        assertEquals("finance", rootList.get(0).path("roles").get(0).asText());
        assertTrue(rootList.get(0).path("canEdit").asBoolean());
        assertFalse(rootList.get(0).path("sharedWithYou").asBoolean());
        assertEquals(200, call(h, "GET", "/flows/global/fin", "root", true, null).status());
        assertEquals(403, call(h, "GET", "/flows/global/fin", "ann", false, null).status());
        assertEquals(403, call(h, "DELETE", "/flows/global/fin", "ann", false, null).status());

        // catalog and validation: nothing is stored by validate
        JsonNode cat = call(h, "GET", "/catalog", "root", true, null).body();
        assertTrue(cat.path("canManageGlobal").asBoolean());
        assertTrue(cat.path("actions").isArray());
        assertFalse(call(h, "GET", "/catalog", "ann", false, null).body().path("canManageGlobal").asBoolean());
        JsonNode bad = call(h, "POST", "/flows/validate", "ann", false,
                "{'scope':'personal','flow':{'name':'broken','start':'a','steps':{'a':{'type':'task','task':'t','next':'zz'}}}}")
                .body();
        assertFalse(bad.path("errors").isEmpty());
        assertEquals(1, call(h, "GET", "/flows", "ann", false, null).body().size(), "validate stores nothing");
        JsonNode good = call(h, "POST", "/flows/validate", "ann", false,
                "{'scope':'personal','flow':{'name':'ok','start':'e','steps':{'e':{'type':'finish'}}}}").body();
        assertTrue(good.path("errors").isEmpty(), good.toString());
        JsonNode notAdmin = call(h, "POST", "/flows/validate", "ann", false,
                "{'scope':'global','flow':{'name':'ok','start':'e','steps':{'e':{'type':'finish'}}}}").body();
        assertFalse(notAdmin.path("errors").isEmpty());
    }

    @Test
    void webPluginIsSkippedWhenTheEnginePluginIsAbsent() throws Exception {
        WebPluginHost h = new WebPluginHost(new ServiceRegistry(), id -> new MemoryPluginStorage(),
                j("{'jllm-workflow-web':{'enabled':true}}"));
        h.load(List.of(new WorkflowWebPlugin()));
        assertTrue(h.loaded().isEmpty());
        assertTrue(h.warnings().get(0).contains("jllm-workflows"), h.warnings().toString());
        assertEquals(404, call(h, "GET", "/flows", "ann", false, null).status());
    }
}
