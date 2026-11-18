package org.kendar.llm.eval;

import org.kendar.llm.harness.llm.ProviderSettings;
import org.kendar.llm.harness.llm.ThinkingLevel;

import java.util.Locale;

/**
 * One resolved model endpoint: what the target, judge or fast model talk to. The credential never prints. A null
 * {@code thinking} leaves the client's own default; the harness maps {@link ThinkingLevel#MINIMAL} to
 * {@code reasoning_effort: none} for a provider named {@code ollama}.
 */
public record ModelEndpoint(Role role, String name, String protocol, String baseUrl, String model, int contextTokens,
                            boolean smallModel, int timeoutSeconds, String apiKey, ThinkingLevel thinking,
                            int maxRpm) {

    public enum Role {TARGET, JUDGE, FAST}

    public static final String ANTHROPIC = "anthropic";
    public static final String OPENAI = "openai";

    public ModelEndpoint {
        Secrets.register(apiKey);
    }

    public ModelEndpoint(Role role, String name, String protocol, String baseUrl, String model, int contextTokens,
                         boolean smallModel, int timeoutSeconds, String apiKey, ThinkingLevel thinking) {
        this(role, name, protocol, baseUrl, model, contextTokens, smallModel, timeoutSeconds, apiKey, thinking, 0);
    }

    public ModelEndpoint(Role role, String name, String protocol, String baseUrl, String model, int contextTokens,
                         boolean smallModel, int timeoutSeconds, String apiKey) {
        this(role, name, protocol, baseUrl, model, contextTokens, smallModel, timeoutSeconds, apiKey, null, 0);
    }

    public boolean isAnthropic() {
        return ANTHROPIC.equals(protocol);
    }

    public ModelEndpoint withRole(Role r) {
        return new ModelEndpoint(r, name, protocol, baseUrl, model, contextTokens, smallModel, timeoutSeconds, apiKey,
                thinking, maxRpm);
    }

    /** True when both endpoints address the same model on the same server. */
    public boolean sameModelAs(ModelEndpoint o) {
        return protocol.equals(o.protocol) && model.equals(o.model) && norm(baseUrl).equals(norm(o.baseUrl));
    }

    private static String norm(String url) {
        String u = url.trim().toLowerCase(Locale.ROOT).replace("127.0.0.1", "localhost");
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /**
     * Harness settings. OpenAI-compatible entries use the {@code llamacpp} kind, or {@code ollama} when named so: the
     * harness keys its thinking-off value on that kind. No key required unless given.
     */
    public ProviderSettings toProviderSettings() {
        boolean keyed = apiKey != null && !apiKey.isBlank();
        String kind = isAnthropic() ? ANTHROPIC : "ollama".equals(name) ? "ollama" : "llamacpp";
        return new ProviderSettings(name, kind, baseUrl, keyed ? apiKey : "", model,
                null, 1, timeoutSeconds, isAnthropic() || keyed, false, contextTokens, smallModel, null, null, thinking,
                null);
    }

    @Override
    public String toString() {
        return role + ":" + name + "[" + protocol + " " + baseUrl + " model=" + model + " ctx=" + contextTokens
                + " small=" + smallModel + " timeout=" + timeoutSeconds + "s key=" + (apiKey == null || apiKey.isBlank() ? "none" : Secrets.MASK) + "]";
    }
}
