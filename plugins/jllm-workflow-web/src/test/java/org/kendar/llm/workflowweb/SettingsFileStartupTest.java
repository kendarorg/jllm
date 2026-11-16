package org.kendar.llm.workflowweb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.plugin.MemoryPluginStorage;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.plugin.WebRequest;
import org.kendar.llm.harness.sandbox.ChatDirSandboxExecutor;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.web.commons.plugins.WebPluginHost;
import org.kendar.llm.workflows.api.WorkflowApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Both plugins are found on the classpath and switched on only by the {@code plugins} section of a settings file:
 * listed and enabled they load and serve; absent or disabled they are invisible.
 */
class SettingsFileStartupTest {
    @TempDir
    Path dir;

    private static final ModelClient NO_MODEL = new ModelClient() {
        @Override
        public ModelResponse complete(ModelRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String simpleComplete(String system, String user, int maxTokens) {
            return "";
        }
    };

    private record Started(ServiceRegistry services, WebPluginHost web) {
    }

    private Started start(String pluginsJson) throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, pluginsJson);
        MemoryPluginStorage mem = new MemoryPluginStorage();
        ServiceRegistry services = new ServiceRegistry();
        services.publish(PluginStorages.class, id -> mem);
        HarnessSettings settings = HarnessSettings.load(file);
        AgentHarness.builder().modelClient(NO_MODEL)
                .sandbox(new ChatDirSandboxExecutor(HarnessConfig.defaults(), dir.resolve("work")))
                .sessionStore(new InMemoryChatSessionStore()).services(services).settings(settings)
                .discoverPlugins().build();
        WebPluginHost web = new WebPluginHost(services, id -> mem, settings.plugins());
        web.load(WebPluginHost.discover(null));
        return new Started(services, web);
    }

    @Test
    void listedAndEnabledPluginsLoadAndServeFromTheSettingsFile() throws Exception {
        String s = "{\"plugins\":{\"jllm-workflows\":{\"enabled\":true,\"settings\":{\"dir\":\""
                + dir.resolve("flows").toString().replace("\\", "/") + "\"}},\"jllm-workflow-web\":{\"enabled\":true}}}";
        Started st = start(s);
        assertTrue(st.services().lookup(WorkflowApi.class).isPresent());
        assertEquals(List.of(WorkflowWebPlugin.ID), st.web().loaded());
        var r = st.web().dispatch(WorkflowWebPlugin.ID,
                new WebRequest("GET", "/flows", Map.of(), Map.of(), null, "ann", false, Set.of()));
        assertEquals(200, r.status());
        assertEquals(0, r.body().size());
    }

    @Test
    void absentOrDisabledPluginsAreInvisible() throws Exception {
        Started none = start("{}");
        assertTrue(none.services().lookup(WorkflowApi.class).isEmpty());
        assertTrue(none.web().loaded().isEmpty());

        Started off = start("{\"plugins\":{\"jllm-workflows\":{\"enabled\":false},\"jllm-workflow-web\":{\"enabled\":false}}}");
        assertTrue(off.services().lookup(WorkflowApi.class).isEmpty());
        assertTrue(off.web().loaded().isEmpty());
        assertEquals(404, off.web().dispatch(WorkflowWebPlugin.ID,
                new WebRequest("GET", "/flows", Map.of(), Map.of(), null, "ann", false, Set.of())).status());
    }

    @Test
    void webPluginWithoutTheEnginePluginIsSkippedWithAWarning() throws Exception {
        Started st = start("{\"plugins\":{\"jllm-workflow-web\":{\"enabled\":true}}}");
        assertTrue(st.web().loaded().isEmpty());
        assertTrue(st.web().warnings().stream().anyMatch(w -> w.contains("jllm-workflows")), st.web().warnings().toString());
    }
}
