package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.plugin.PluginStorage;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Global flows kept as files in the folder {@code jllm.flows-seed-dir} (the sample run configurations use
 * {@code runConfigs/unsafe/flows}). At startup each {@code *.json} is added to the global flows of the flows plugin when no
 * flow of that name exists; nothing is overwritten. Not set: nothing happens.
 */
@Component
class SampleFlows implements ApplicationRunner {
    static final String FLOWS_PLUGIN = "jllm-workflows";

    private final AgentHarness harness;
    private final ObjectMapper mapper;
    private final String dir;

    SampleFlows(AgentHarness harness, ObjectMapper mapper, @Value("${jllm.flows-seed-dir:}") String dir) {
        this.harness = harness;
        this.mapper = mapper;
        this.dir = dir;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (dir == null || dir.isBlank()) return;
        harness.services().lookup(PluginStorages.class).ifPresent(s -> seed(s.forPlugin(FLOWS_PLUGIN), Path.of(dir)));
    }

    void seed(PluginStorage storage, Path folder) {
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> json = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (Path f : json) {
                JsonNode doc = mapper.readTree(f.toFile());
                String name = doc.path("name").asText("");
                if (name.isBlank() || storage.get(PluginStorage.GLOBAL, "flows", name).isPresent()) continue;
                storage.put(PluginStorage.GLOBAL, "flows", name, doc);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
