package org.kendar.llm.web.commons;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.auth.FileTokenStore;
import org.kendar.llm.harness.auth.OAuthException;
import org.kendar.llm.harness.auth.OAuthLogin;
import org.kendar.llm.harness.auth.OAuthToken;
import org.kendar.llm.harness.auth.RedirectTarget;
import org.kendar.llm.harness.auth.UserInteraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OAuth logins of REST APIs and MCP servers through the browser, with the redirect sent to this server:
 * <ul>
 *   <li>{@code POST /api/oauth/{source}/start}: starts the login of the signed-in user, returns the URL to open;</li>
 *   <li>{@code GET /oauth/callback}: where the authorization server sends the browser back; the {@code state} is
 *       single-use, expires with the login (10 minutes) and must come from the user who started it;</li>
 *   <li>{@code GET /api/oauth}: the user's sources and whether they are connected (never a token);</li>
 *   <li>{@code DELETE /api/oauth/{source}}: logout.</li>
 * </ul>
 * {@code /login api:<x>} typed in a chat goes through the same start. The redirect URI is
 * {@code <jllm.web.public-url>/oauth/callback}, exact, so the public URL must be set when a per-user source uses the
 * authorization code flow.
 */
@RestController
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);
    public static final String CALLBACK = "/oauth/callback";

    /** One login waiting for its redirect. */
    private record Pending(String userId, String source, Instant expires, CompletableFuture<String> code,
                           CompletableFuture<Boolean> outcome) {
    }

    private final AgentHarness harness;
    private final WebAuthentication authentication;
    private final String publicUrl;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Thread> running = new ConcurrentHashMap<>();

    public OAuthController(AgentHarness harness, WebAuthentication authentication, JllmProperties props) {
        this.harness = harness;
        this.authentication = authentication;
        String url = props.web() == null ? null : props.web().publicUrl();
        this.publicUrl = url == null || url.isBlank() ? null : url.strip().replaceAll("/+$", "");
    }

    /**
     * Refuses to start, naming the source, when a per-user source cannot work: tokens of several users in an
     * unencrypted store, or an authorization code login without a public URL to redirect to.
     */
    @PostConstruct
    public void check() {
        if (!harness.multiUser()) {
            harness.commands().webLogin(this::startFromChat);
            return;
        }
        for (String source : harness.oauth().names()) {
            if (!harness.oauth().perUser(source)) continue;
            if (harness.tokenStore() instanceof FileTokenStore f && !f.encrypted()) {
                throw new IllegalStateException(source + " keeps a token per user: set JLLM_TOKEN_KEY (32 bytes, "
                        + "base64) to encrypt the token files, or tokenStore.encrypt; the users' tokens must not be "
                        + "readable from backups and volumes");
            }
            if (publicUrl == null && harness.oauth().needsRedirect(source)) {
                throw new IllegalStateException(source + " logs every user in through a browser redirect: set "
                        + "jllm.web.public-url (the externally visible base URL of this server)");
            }
        }
        harness.commands().webLogin(this::startFromChat);
    }

    // ---- start

    /** The {@code /login <name>} of a web chat: same path as the button. */
    private String startFromChat(String chatId, String userId, String key, String name) {
        Started s = start(chatId, userId, key, name);
        return s.error != null ? s.error : s.url != null ? "Open this link to connect " + name + ": " + s.url
                : s.message;
    }

    private record Started(String url, String message, String error) {
    }

    @PostMapping("/api/oauth/{source}/start")
    public ResponseEntity<Object> start(@PathVariable String source,
                                        @RequestParam(value = "chatId", required = false) String chatId,
                                        @RequestParam(value = "shared", required = false) Boolean shared,
                                        @RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                        WebPrincipal user) {
        if (user == null) return error(HttpStatus.UNAUTHORIZED, "Sign in first");
        String key;
        try {
            key = keyFor(user, source, Boolean.TRUE.equals(shared));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        }
        if (!harness.logins().containsKey(source)) return error(HttpStatus.NOT_FOUND, "Unknown login " + source);
        Started s = start(chatId, user.id(), key, source);
        if (s.error != null) return error(HttpStatus.CONFLICT, s.error);
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.url != null) out.put("authorizeUrl", s.url);
        if (s.message != null) out.put("message", s.message);
        return ResponseEntity.ok(out);
    }

    /** The store key of the user's login: their id for a per-user source; the shared login only for the admin. */
    private String keyFor(WebPrincipal user, String source, boolean shared) {
        if (!shared && harness.oauth().perUser(source)) return user.id();
        if (!user.admin()) {
            throw new IllegalArgumentException(source + " has one login shared by every user: only the "
                    + "administrator can change it");
        }
        return null;
    }

    /** Runs the login on a virtual thread; returns once it has a URL (or a device code) to show. */
    private Started start(String chatId, String userId, String key, String name) {
        OAuthLogin login = harness.logins().get(name);
        if (login == null) return new Started(null, null, "Unknown login " + name);
        purge();
        String slot = (key == null ? "-" : key) + "/" + name;
        CompletableFuture<Started> first = new CompletableFuture<>();
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().name("oauth-" + name).unstarted(() -> {
            String outcome;
            boolean ok = false;
            try {
                OAuthToken token = login.login(new UserInteraction() {
                    @Override
                    public void show(String message) {
                        first.complete(new Started(null, message, null));
                    }

                    @Override
                    public String ask(String prompt) {
                        return null;
                    }

                    @Override
                    public void openUrl(String url) {
                        first.complete(new Started(url, null, null));
                    }
                }, new WebTarget(userId, name, done));
                harness.saveLogin(key, name, token);
                outcome = "Connected to " + name;
                ok = true;
            } catch (OAuthException e) {
                outcome = "Login to " + name + " failed (" + e.code() + ")";
            } catch (RuntimeException e) {
                log.warn("Login to {} failed: {}", name, e.toString());
                outcome = "Login to " + name + " failed";
            } finally {
                running.remove(slot);
            }
            done.complete(ok);
            first.complete(new Started(null, null, outcome));
            harness.loginFinished(chatId, name, ok, outcome);
        });
        if (running.putIfAbsent(slot, t) != null) {
            return new Started(null, null, "A login to " + name + " is already in progress");
        }
        t.start();
        try {
            return first.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return new Started(null, "Login to " + name + " started", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Started(null, null, "interrupted");
        } catch (ExecutionException e) {
            return new Started(null, null, "Login to " + name + " failed");
        }
    }

    /** The redirect of a web login: registered under its state until the callback, or the login's timeout. */
    private final class WebTarget implements RedirectTarget {
        private final String userId;
        private final String source;
        private final CompletableFuture<Boolean> done;

        WebTarget(String userId, String source, CompletableFuture<Boolean> done) {
            this.userId = userId;
            this.source = source;
            this.done = done;
        }

        @Override
        public Receiver open(String state) {
            if (publicUrl == null) {
                throw new OAuthException("unsupported", "jllm.web.public-url is not set: no redirect URL for the web login");
            }
            Pending p = new Pending(userId, source, Instant.now().plus(Duration.ofMinutes(10)),
                    new CompletableFuture<>(), done);
            pending.put(state, p);
            return new Receiver() {
                @Override
                public String redirectUri() {
                    return publicUrl + CALLBACK;
                }

                @Override
                public String awaitCode(Duration timeout) {
                    try {
                        return p.code().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        throw new OAuthException("timeout", "No login redirect received in " + timeout.toSeconds() + " s");
                    } catch (ExecutionException e) {
                        throw e.getCause() instanceof OAuthException o ? o : new OAuthException("io", "login failed");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new OAuthException("io", "interrupted", e);
                    }
                }

                @Override
                public void close() {
                    pending.remove(state);
                }
            };
        }
    }

    private void purge() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> e.getValue().expires().isBefore(now));
    }

    // ---- callback

    /**
     * The redirect target. Unauthenticated route: the state is checked against the pending logins (single use,
     * expiring) and against the signed-in user of the browser. Never echoes what the server sent.
     */
    @GetMapping(value = CALLBACK, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(value = "state", required = false) String state,
                                           @RequestParam(value = "code", required = false) String code,
                                           @RequestParam(value = "error", required = false) String error,
                                           HttpServletRequest request) {
        purge();
        Pending p = state == null ? null : pending.remove(state); // single use
        if (p == null) return page(HttpStatus.BAD_REQUEST, "This login link is unknown, already used or expired.");
        Optional<WebPrincipal> user = authentication.current(request);
        String who = user.map(WebPrincipal::id).orElse(null);
        if (p.userId() != null && !p.userId().equals(who)) {
            p.code().completeExceptionally(new OAuthException("access_denied", "callback from another user"));
            return page(HttpStatus.FORBIDDEN, "This login was started by another user. Sign in as that user and"
                    + " start it again.");
        }
        if (error != null) {
            p.code().completeExceptionally(new OAuthException(error, null));
            return page(HttpStatus.OK, "The login to " + p.source() + " was not completed. You can close this tab.");
        }
        if (code == null || code.isBlank()) {
            p.code().completeExceptionally(new OAuthException("invalid_response", "no code"));
            return page(HttpStatus.BAD_REQUEST, "No code received. You can close this tab.");
        }
        p.code().complete(code);
        // the exchange runs on the login's thread: wait for it to tell the user the result
        boolean ok;
        try {
            ok = p.outcome().get(30, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            ok = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        }
        return page(HttpStatus.OK, ok ? "Connected to " + p.source() + ". You can close this tab."
                : "The login to " + p.source() + " did not succeed. The chat shows the result; you can close this tab.");
    }

    private static ResponseEntity<String> page(HttpStatus status, String text) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .body("<!doctype html><meta charset=\"utf-8\"><title>jllm</title><p>" + safe + "</p>");
    }

    // ---- list and logout

    /** The OAuth sources this user can see, with their own state. Never a token. */
    @GetMapping("/api/oauth")
    public ResponseEntity<Object> list(@RequestAttribute(value = WebPrincipal.ATTR, required = false) WebPrincipal user) {
        if (user == null) return error(HttpStatus.UNAUTHORIZED, "Sign in first");
        List<Map<String, Object>> out = new ArrayList<>();
        for (String source : harness.oauth().names()) {
            boolean perUser = harness.oauth().perUser(source);
            boolean service = harness.oauth().serviceAccount(source);
            if (!perUser && !user.admin() && !service) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", source);
            m.put("perUser", perUser);
            m.put("serviceAccount", service);
            if (!service) {
                Optional<OAuthToken> t = harness.tokenStore().get(perUser ? user.id() : null, source);
                m.put("connected", t.isPresent());
                t.filter(x -> x.expiresAt() != 0).ifPresent(x -> m.put("expiresAt", x.expiresAt()));
                if (perUser && user.admin()) {
                    m.put("sharedConnected", harness.tokenStore().get(null, source).isPresent());
                }
            }
            out.add(m);
        }
        return ResponseEntity.ok(Map.of("sources", out, "store", harness.tokenStore().describe()));
    }

    @DeleteMapping("/api/oauth/{source}")
    public ResponseEntity<Object> logout(@PathVariable String source,
                                         @RequestParam(value = "shared", required = false) Boolean shared,
                                         @RequestAttribute(value = WebPrincipal.ATTR, required = false)
                                         WebPrincipal user) {
        if (user == null) return error(HttpStatus.UNAUTHORIZED, "Sign in first");
        String key;
        try {
            key = keyFor(user, source, Boolean.TRUE.equals(shared));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        }
        harness.logout(key, source);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
