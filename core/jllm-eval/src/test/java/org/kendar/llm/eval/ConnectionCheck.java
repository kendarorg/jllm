package org.kendar.llm.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.llm.ModelClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * Before any test: every distinct endpoint (target, judge, separate fast model) must answer and serve its configured
 * model, then gets one short warm-up call whose time is recorded apart from test timings. Read-only: remote
 * servers are never started, stopped or reloaded.
 */
public final class ConnectionCheck {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Warm-up time in milliseconds per role. */
    public record Result(Map<ModelEndpoint.Role, Long> warmupMillis) {
    }

    private ConnectionCheck() {
    }

    public static Result run(EvalConfig config, Function<ModelEndpoint, ModelClient> clients) {
        List<ModelEndpoint> all = new ArrayList<>(List.of(config.target(), config.judge()));
        if (config.fastSeparate()) all.add(config.fast());

        Map<ModelEndpoint, Long> done = new LinkedHashMap<>();
        Map<ModelEndpoint.Role, Long> byRole = new EnumMap<>(ModelEndpoint.Role.class);
        List<String> failures = new ArrayList<>();
        for (ModelEndpoint e : all) {
            Long known = done.entrySet().stream().filter(d -> d.getKey().sameModelAs(e)).map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            if (known != null) {
                byRole.put(e.role(), known);
                continue;
            }
            try {
                probe(e, config.connectTimeoutSeconds());
                long t0 = System.nanoTime();
                String answer = clients.apply(e).simpleComplete("", "Reply with the single word OK.", 32);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (answer == null || answer.isBlank()) throw new IllegalStateException("empty warm-up answer");
                done.put(e, ms);
                byRole.put(e.role(), ms);
            } catch (Exception ex) {
                failures.add(Secrets.mask(e.role() + " entry '" + e.name() + "' at " + e.baseUrl() + " (model '"
                        + e.model() + "'): " + ex.getMessage()));
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Connection check failed, no test was run:\n  - "
                    + String.join("\n  - ", failures));
        }
        return new Result(byRole);
    }

    /** Verifies the endpoint is reachable and serves the model. */
    static void probe(ModelEndpoint e, int timeoutSeconds) throws Exception {
        String base = e.baseUrl().replaceAll("/+$", "");
        HttpRequest.Builder rb;
        if (e.isAnthropic()) {
            rb = HttpRequest.newBuilder(URI.create(base + "/v1/models/" + e.model()))
                    .header("x-api-key", e.apiKey()).header("anthropic-version", "2023-06-01");
        } else {
            rb = HttpRequest.newBuilder(URI.create(base + "/models"));
            if (e.apiKey() != null && !e.apiKey().isBlank()) rb.header("Authorization", "Bearer " + e.apiKey());
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        HttpResponse<String> r;
        try {
            r = http.send(rb.timeout(Duration.ofSeconds(timeoutSeconds)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("unreachable (" + ex.getClass().getSimpleName() + ")");
        }
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + r.statusCode() + (e.isAnthropic() ? " (model unknown or key rejected)" : ""));
        }
        if (!e.isAnthropic()) {
            JsonNode data = MAPPER.readTree(r.body()).path("data");
            boolean served = false;
            for (JsonNode m : data) {
                if (e.model().equals(m.path("id").asText()) || e.model().equals(m.path("model").asText())) served = true;
            }
            if (!served) {
                List<String> ids = new ArrayList<>();
                data.forEach(m -> ids.add(m.path("id").asText()));
                throw new IllegalStateException("model not served; server offers " + ids);
            }
        }
    }
}
