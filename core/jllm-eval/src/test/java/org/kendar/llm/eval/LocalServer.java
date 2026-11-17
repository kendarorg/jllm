package org.kendar.llm.eval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * The one llama.cpp server of a run. Started at most once per run, reused when an already running container has the
 * same fingerprint (image, model file, alias, context, args, port), restarted exactly once when it differs, left
 * running afterwards unless {@code eval.local.keepRunning=false}. Never touched between tests.
 */
public final class LocalServer {

    public enum Outcome {STARTED, REUSED, RESTARTED}

    /** Container operations; the Testcontainers implementation is {@link TestcontainersBackend}. */
    public interface Backend {
        Optional<Existing> find();

        void remove(String id);

        /** Creates and starts the container; the server may still be loading its model. */
        void create(LocalSettings settings, String fingerprint);
    }

    public record Existing(String id, boolean running, String fingerprint) {
    }

    private final LocalSettings settings;
    private final Backend backend;
    private final HealthProbe probe;
    private Outcome outcome;

    /** Answers whether the server is up and has loaded the model. */
    public interface HealthProbe {
        boolean ready(String baseUrl);
    }

    public LocalServer(LocalSettings settings, Backend backend, HealthProbe probe) {
        this.settings = settings;
        this.backend = backend;
        this.probe = probe;
    }

    public static LocalServer testcontainers(LocalSettings settings) {
        return new LocalServer(settings, new TestcontainersBackend(), LocalServer::httpHealth);
    }

    public Outcome ensureRunning() {
        String fp = settings.fingerprint();
        Optional<Existing> existing = backend.find();
        if (existing.isPresent() && existing.get().running() && fp.equals(existing.get().fingerprint())) {
            outcome = Outcome.REUSED;
        } else if (existing.isPresent()) {
            backend.remove(existing.get().id());
            backend.create(settings, fp);
            outcome = Outcome.RESTARTED;
        } else {
            backend.create(settings, fp);
            outcome = Outcome.STARTED;
        }
        awaitReady();
        return outcome;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** End of run: removes the container only when keepRunning is off. */
    public void finish() {
        if (!settings.keepRunning()) backend.find().ifPresent(e -> backend.remove(e.id()));
    }

    private void awaitReady() {
        long deadline = System.nanoTime() + Duration.ofSeconds(settings.startupTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            if (probe.ready(settings.baseUrl())) return;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("Local llama.cpp server at " + settings.baseUrl() + " (model '"
                + settings.model().alias() + "') not ready after " + settings.startupTimeoutSeconds() + "s");
    }

    static boolean httpHealth(String baseUrl) {
        try {
            String root = baseUrl.endsWith("/v1") ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
            HttpResponse<Void> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(root + "/health")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
