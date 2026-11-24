package org.kendar.llm.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.plugin.FilePluginStorage;
import org.kendar.llm.harness.plugin.PluginStorage;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.web.sql.SqlPluginStorage;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Flows and runs follow the store selection: SQL survives a restart, file mode keeps the files. */
class WorkflowStorageBootTest {

    @TempDir
    Path home;

    private PluginStorages storages(ConfigurableApplicationContext c) {
        return c.getBean(AgentHarness.class).services().lookup(PluginStorages.class).orElseThrow();
    }

    @Test
    void sqlFlowsAndRunsSurviveARestart() throws Exception {
        Path seed = home.resolve("seed");
        Files.createDirectories(seed);
        Files.writeString(seed.resolve("f.json"), "{\"name\":\"sample\"}");
        try (ConfigurableApplicationContext c = RealHttpApp.start(home, "w1",
                "--jllm.flows-seed-dir=" + RealHttpApp.slashes(seed))) {
            assertInstanceOf(SqlPluginStorage.class, storages(c));
            PluginStorage st = storages(c).forPlugin(SampleFlows.FLOWS_PLUGIN);
            assertTrue(st.get(PluginStorage.GLOBAL, "flows", "sample").isPresent(), "seeded into SQL");
            st.put("admin", "runs", "r1", RealHttpApp.JSON.createObjectNode().put("runId", "r1"));
        }
        assertFalse(Files.exists(home.resolve("w1").resolve(".jllm").resolve("plugin-data")));
        try (ConfigurableApplicationContext c = RealHttpApp.start(home, "w2")) {
            PluginStorage st = storages(c).forPlugin(SampleFlows.FLOWS_PLUGIN);
            assertTrue(st.get(PluginStorage.GLOBAL, "flows", "sample").isPresent());
            assertEquals("r1", st.get("admin", "runs", "r1").orElseThrow().path("runId").asText());
        }
    }

    @Test
    void fileModeStillUsesFiles() {
        try (ConfigurableApplicationContext c = RealHttpApp.start(home, "w3", "--jllm.store=file",
                "--jllm.webauth-dir=" + RealHttpApp.slashes(home.resolve("webauth")))) {
            assertInstanceOf(FilePluginStorage.class, storages(c));
        }
    }
}
