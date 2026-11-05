package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The providers of a harness: those of the settings list, the implicit built-ins ({@code anthropic}, {@code openai},
 * {@code qwen}, {@code llamacpp}, {@code ollama}) and one {@code custom} entry that lives for the session only.
 */
public final class ProviderCatalog {

    public static final String CUSTOM = "custom (session)";
    private static final Set<String> BUILT_INS = Set.of("anthropic", "openai", "qwen", "llamacpp", "ollama");

    private final Map<String, ProviderSettings> configured = new LinkedHashMap<>();
    private volatile ProviderSettings custom;
    private final ObjectMapper mapper;
    private final int defaultMaxTokens;

    public ProviderCatalog(List<ProviderSettings> providers, ObjectMapper mapper, int defaultMaxTokens) {
        for (ProviderSettings p : providers) {
            if (p.name() == null || p.name().isBlank()) throw new IllegalArgumentException("A provider has no name");
            if (configured.putIfAbsent(p.name(), p) != null) {
                throw new IllegalArgumentException("Duplicate provider '" + p.name() + "'");
            }
        }
        this.mapper = mapper;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    /** Names offered in a menu: the configured providers, then the session entry if one exists. */
    public List<String> names() {
        List<String> out = new ArrayList<>(configured.keySet());
        if (custom != null) out.add(CUSTOM);
        return out;
    }

    /** The provider by name: configured, the session entry, or an implicit built-in; null if unknown. */
    public ProviderSettings find(String name) {
        if (name == null) return null;
        ProviderSettings p = configured.get(name);
        if (p != null) return p;
        if (CUSTOM.equals(name)) return custom;
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (BUILT_INS.contains(key)) {
            return new ProviderSettings(key, key, null, null, null, defaultMaxTokens, null, null, null, null, null,
                    null, null);
        }
        return null;
    }

    /** Sets the session-only entry (never written to a settings file) and returns its name. */
    public String setCustom(ProviderSettings settings) {
        custom = new ProviderSettings(CUSTOM, settings.type(), settings.baseUrl(), settings.apiKey(),
                settings.model(), settings.maxTokens() != null ? settings.maxTokens() : defaultMaxTokens,
                settings.maxRetries(), settings.timeoutSeconds(), settings.requireApiKey(),
                settings.useMaxCompletionTokens(), settings.contextTokens(), settings.smallModel(),
                settings.temperature(), settings.order(), settings.thinking());
        return CUSTOM;
    }

    /** @throws IllegalArgumentException for an unknown name */
    public ModelClient client(String name) {
        ProviderSettings p = find(name);
        if (p == null) {
            throw new IllegalArgumentException("Unknown provider '" + name + "' (known: "
                    + String.join(", ", names()) + "; built-in: " + String.join(", ", BUILT_INS) + ")");
        }
        return client(p);
    }

    /** The client of the provider, falling back to the providers with a higher {@code order} when it fails. */
    public ModelClient client(ProviderSettings p) {
        ModelClient primary = single(p);
        List<ProviderSettings> next = new ArrayList<>();
        for (ProviderSettings c : configured.values()) {
            if (c.order() != null && !c.name().equals(p.name()) && (p.order() == null || c.order() > p.order())) {
                next.add(c);
            }
        }
        if (next.isEmpty()) return primary;
        next.sort(java.util.Comparator.comparingInt(ProviderSettings::order));
        List<FallbackModelClient.Candidate> chain = new ArrayList<>();
        chain.add(new FallbackModelClient.Candidate(p.name(), () -> primary));
        for (ProviderSettings n : next) chain.add(new FallbackModelClient.Candidate(n.name(), () -> single(n)));
        return new FallbackModelClient(chain);
    }

    private ModelClient single(ProviderSettings p) {
        ModelClient c = ModelClients.from(p.toLlmSettings(), mapper);
        // Anthropic never receives a temperature: Claude 5 models and the current API version reject it
        if (p.temperature() != null && !p.effectiveType().equals("anthropic")) {
            c = new TemperatureModelClient(c, p.temperature());
        }
        if (p.thinking() != null) c = new ThinkingModelClient(c, p.thinking());
        return c;
    }
}
