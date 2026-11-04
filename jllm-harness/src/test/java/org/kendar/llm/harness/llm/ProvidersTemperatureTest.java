package org.kendar.llm.harness.llm;

import org.kendar.llm.harness.AgentHarness;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class ProvidersTemperatureTest {

    @TempDir
    Path base;

    private static ProviderSettings local(String name, Integer ctx, Double temp) {
        return new ProviderSettings(name, "llamacpp", "http://localhost:1/v1", null, "m", null, null, null, null, null,
                ctx, null, temp);
    }

    private AgentHarness harness(List<ProviderSettings> providers) {
        HarnessSettings settings = new HarnessSettings(null, null, null, null, null, null, null, null, null, null,
                null, null, providers, null);
        return AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base)).settings(settings).build();
    }

    @Test
    void catalogResolvesConfiguredBuiltInAndSessionEntries() {
        ProviderCatalog c = new ProviderCatalog(List.of(local("my-qwen", 32000, null)), MAPPER, 4096);

        assertEquals("my-qwen", c.find("my-qwen").name());
        assertEquals("anthropic", c.find("anthropic").effectiveType());
        assertNull(c.find("nope"));
        assertThrows(IllegalArgumentException.class, () -> c.client("nope"));
        c.setCustom(local("x", null, null));
        assertEquals(List.of("my-qwen", ProviderCatalog.CUSTOM), c.names());
    }

    @Test
    void temperatureIsSentToOpenAiCompatAndNeverToAnthropic() {
        ModelRequest req = new ModelRequest("p", "", MAPPER.createArrayNode(), List.of(), 100).withTemperature(0.3);
        OpenAiCompatModelClient oa = new OpenAiCompatModelClient(local("q", null, null).toLlmSettings(), MAPPER);
        assertEquals(0.3, oa.buildBody(req).get("temperature").asDouble());
        assertFalse(oa.buildBody(req.withTemperature(null)).has("temperature"));

        AnthropicModelClient an = new AnthropicModelClient(AnthropicSettings.defaults("k"), MAPPER);
        assertFalse(an.buildBody(req).has("temperature"));
    }

    @Test
    void wrapperFillsOnlyMissingTemperature() {
        ScriptedModel inner = new ScriptedModel();
        inner.enqueue(text("a"), text("b"));
        java.util.List<Double> seen = new java.util.ArrayList<>();
        inner.handler = r -> {
            seen.add(r.temperature());
            return text("x");
        };
        ModelClient c = new TemperatureModelClient(inner, 0.5);
        ArrayNode m = MAPPER.createArrayNode();

        c.complete(new ModelRequest("p", "", m, List.of(), 10));
        c.complete(new ModelRequest("p", "", m, List.of(), 10).withTemperature(0.9));

        assertEquals(List.of(0.5, 0.9), seen);
    }

    @Test
    void invalidTemperatureIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> local("a", null, 3.0));
    }

    @Test
    void switchFitsRefusesOrAsksToCompact() {
        AgentHarness h = harness(List.of(local("big", 1_000_000, null), local("tiny", 10, null)));
        String chat = h.sessions().create("t").chatId();
        ArrayNode msgs = h.sessions().messages(h.sessions().get(chat));
        msgs.addObject().put("role", "user").putArray("content").addObject().put("type", "text")
                .put("text", "x".repeat(4000));
        h.sessions().saveMessages(chat, msgs);

        assertEquals(AgentHarness.SwitchStatus.SWITCHED, h.switchModel(chat, "big", false).status());
        assertEquals("big", h.currentProvider());
        assertEquals(AgentHarness.SwitchStatus.NEEDS_COMPACTION, h.switchModel(chat, "tiny", false).status());
        assertEquals("big", h.currentProvider());
        // compaction cannot shrink one huge message below 10 tokens, so the switch is refused and the model kept
        assertEquals(AgentHarness.SwitchStatus.REFUSED, h.switchModel(chat, "tiny", true).status());
        assertEquals("big", h.currentProvider());
    }
}
