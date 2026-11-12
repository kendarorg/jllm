package org.kendar.llm.harness.auth;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One-shot listener on 127.0.0.1 that receives the OAuth redirect, checks {@code state} and returns the {@code code}. */
public final class LoopbackCallbackServer implements AutoCloseable {

    private final HttpServer server;
    private final CompletableFuture<String> code = new CompletableFuture<>();

    /** @param port 0 = a free port; @param expectedState the {@code state} the redirect must carry */
    public LoopbackCallbackServer(int port, String expectedState) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/callback", ex -> {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String page;
            if (code.isDone()) {
                page = "Already handled.";
            } else if (!expectedState.equals(q.get("state"))) {
                page = "Invalid state. Close this tab and try again.";
                code.completeExceptionally(new OAuthException("invalid_state", "The state of the redirect does not match"));
            } else if (q.containsKey("error")) {
                page = "Login failed: " + escape(q.get("error")) + ". You can close this tab.";
                code.completeExceptionally(new OAuthException(q.get("error"), q.get("error_description")));
            } else if (q.get("code") == null) {
                page = "No code received. You can close this tab.";
                code.completeExceptionally(new OAuthException("invalid_response", "The redirect carried no code"));
            } else {
                page = "Login complete: you can close this tab.";
                code.complete(q.get("code"));
            }
            byte[] b = ("<!doctype html><meta charset=\"utf-8\"><title>jllm</title><p>" + page + "</p>")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
    }

    /** Waits for the redirect. @throws OAuthException on a bad state, an error redirect or a timeout (code {@code timeout}) */
    public String awaitCode(Duration timeout) {
        try {
            return code.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new OAuthException("timeout", "No login redirect received in " + timeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            throw (OAuthException) e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("io", "interrupted", e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
