package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ProviderCatalog;
import org.kendar.llm.harness.llm.ProviderSettings;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.util.Map;

/** Picks the model provider from {@code --provider}, the settings list and the session overrides. */
final class ProviderChooser {

    /** The model client for the command line, the name it is known by and the session-only entry, if any. */
    record ProviderChoice(ModelClient client, String name, ProviderSettings session) {
    }

    private ProviderChooser() {
    }

    /**
     * {@code --provider} picks a provider of the settings list (or a built-in). {@code --model}, {@code --base-url}
     * and {@code --api-key} override it for this session only; with an unknown provider name, or {@code --model}
     * alone, they define a {@code custom} entry that is never written to the settings file.
     */
    static ProviderChoice choose(Map<String, String> a, HarnessSettings settings, ObjectMapper mapper, int maxTokens) {
        ProviderCatalog catalog = new ProviderCatalog(settings.providers(), mapper, maxTokens);
        String name = a.containsKey("provider") ? a.get("provider")
                : settings.defaultProvider() != null ? settings.defaultProvider() : "anthropic";
        ProviderSettings base = catalog.find(name);
        boolean overrides = a.containsKey("model") || a.containsKey("base-url") || a.containsKey("api-key");
        if (base != null && !overrides) return new ProviderChoice(catalog.client(base), base.name(), null);
        if (base == null && !a.containsKey("base-url") && !a.containsKey("model")) {
            throw new IllegalArgumentException("Unknown provider '" + name + "' (known: "
                    + String.join(", ", catalog.names()) + "); give --base-url and --model to define a session entry");
        }
        ProviderSettings from = base != null ? base : new ProviderSettings(name, "openai", null, null, null,
                maxTokens, null, null, null, null, null, null, null);
        ProviderSettings merged = new ProviderSettings(from.name(), from.type(),
                a.getOrDefault("base-url", from.baseUrl()), a.getOrDefault("api-key", from.apiKey()),
                a.getOrDefault("model", from.model()), from.maxTokens() != null ? from.maxTokens() : maxTokens,
                from.maxRetries(), from.timeoutSeconds(), from.requireApiKey(), from.useMaxCompletionTokens(),
                from.contextTokens(), from.smallModel(), from.temperature(), null, from.thinking(), from.stream());
        catalog.setCustom(merged);
        return new ProviderChoice(catalog.client(ProviderCatalog.CUSTOM), ProviderCatalog.CUSTOM,
                catalog.find(ProviderCatalog.CUSTOM));
    }
}
