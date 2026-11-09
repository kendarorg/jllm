package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** RFC 8628 device authorization: request a code, show it, poll until approved, denied or expired. */
public final class DeviceCodeFlow {

    /** What the user must do. */
    public record DeviceCode(String userCode, String verificationUri, String verificationUriComplete, int expiresIn) {
    }

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private DeviceCodeFlow() {
    }

    /**
     * @param prompt called once with the code to show (CLI prints it, web displays it)
     * @param sleeper waits between polls
     * @param clock   epoch seconds
     */
    public static OAuthToken run(HttpClient http, ObjectMapper mapper, String deviceEndpoint, String clientId,
                                 String scope, OAuthTokenClient tokens, Consumer<DeviceCode> prompt, Sleeper sleeper,
                                 LongSupplier clock) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", clientId);
        if (scope != null && !scope.isBlank()) f.put("scope", scope);
        JsonNode r = OAuthTokenClient.postForm(http, mapper, deviceEndpoint, f);
        String deviceCode = r.path("device_code").asText("");
        if (deviceCode.isEmpty()) throw new OAuthException("invalid_response", "No device_code in the response");
        int expiresIn = r.path("expires_in").asInt(900);
        long interval = Math.max(0, r.path("interval").asLong(5)) * 1000;
        prompt.accept(new DeviceCode(r.path("user_code").asText(), r.path("verification_uri").asText(
                r.path("verification_url").asText()), r.path("verification_uri_complete").asText(null), expiresIn));
        long deadline = clock.getAsLong() + expiresIn;
        while (true) {
            try {
                sleeper.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OAuthException("io", "interrupted", e);
            }
            if (clock.getAsLong() >= deadline) throw new OAuthException("expired_token", "The device code expired");
            try {
                return tokens.deviceToken(deviceCode);
            } catch (OAuthException e) {
                switch (e.code()) {
                    case "authorization_pending" -> {
                    }
                    case "slow_down" -> interval += 5000;
                    default -> throw e; // access_denied, expired_token, ...
                }
            }
        }
    }
}
