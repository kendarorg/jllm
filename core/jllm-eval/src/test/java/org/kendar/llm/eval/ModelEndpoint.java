package org.kendar.llm.eval;

import org.kendar.llm.harness.llm.ProviderSettings;

import java.util.Locale;

/** One resolved model endpoint: what the target, judge or fast model talk to. The credential never prints. */
public record ModelEndpoint(Role role, String name, String protocol, String baseUrl, String model, int contextTokens,
                            boolean smallModel, int timeoutSeconds, String apiKey) {

    public enum Role {TARGET, JUDGE, FAST}

    public static final String ANTHROPIC = "anthropic";
    public static final String OPENAI = "openai";

    public ModelEndpoint {
        Secrets.register(apiKey);
    }

    public boolean isAnthropic() {
        return ANTHROPIC.equals(protocol);
    }

    public ModelEndpoint withRole(Role r) {
        return new ModelEndpoint(r, name, protocol, baseUrl, model, contextTokens, smallModel, timeoutSeconds, apiKey);
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

    /** Harness settings. OpenAI-compatible entries use the {@code llamacpp} kind: no key required unless given. */
    public ProviderSettings toProviderSettings() {
        boolean keyed = apiKey != null && !apiKey.isBlank();
        return new ProviderSettings(name, isAnthropic() ? ANTHROPIC : "llamacpp", baseUrl, keyed ? apiKey : "", model,
                null, 1, timeoutSeconds, isAnthropic() || keyed, false, contextTokens, smallModel, null, null, null,
                null);
    }

    @Override
    public String toString() {
        return role + ":" + name + "[" + protocol + " " + baseUrl + " model=" + model + " ctx=" + contextTokens
                + " small=" + smallModel + " timeout=" + timeoutSeconds + "s key=" + (apiKey == null || apiKey.isBlank() ? "none" : Secrets.MASK) + "]";
    }
}
