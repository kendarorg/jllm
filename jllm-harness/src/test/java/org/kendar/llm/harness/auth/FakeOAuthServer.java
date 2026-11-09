package org.kendar.llm.harness.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Scriptable OAuth server: metadata, registration, device, token and a protected {@code /mcp} endpoint. */
final class FakeOAuthServer implements AutoCloseable {

    final HttpServer server;
    final List<Map<String, String>> tokenRequests = new ArrayList<>();
    /** Error codes returned by the next device token polls, then success. */
    final Deque<String> devicePolls = new ArrayDeque<>();
    final AtomicInteger issued = new AtomicInteger();
    /** Seconds until issued tokens expire. */
    volatile int expiresIn = 3600;
    /** When set, the next refresh answers this error. */
    volatile String refreshError;
    volatile String validAccess = "";
    final List<String> mcpAuthHeaders = new ArrayList<>();

    FakeOAuthServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/.well-known/oauth-protected-resource", ex -> json(ex, 200, "{\"authorization_servers\":[\"" + base() + "\"]}"));
        server.createContext("/.well-known/oauth-authorization-server", ex -> json(ex, 200, "{\"authorization_endpoint\":\"" + base()
                + "/authorize\",\"token_endpoint\":\"" + base() + "/token\",\"registration_endpoint\":\"" + base() + "/register\"}"));
        server.createContext("/register", ex -> json(ex, 201, "{\"client_id\":\"client-1\"}"));
        server.createContext("/device", ex -> json(ex, 200, "{\"device_code\":\"dev1\",\"user_code\":\"ABCD\","
                + "\"verification_uri\":\"" + base() + "/verify\",\"expires_in\":600,\"interval\":0}"));
        server.createContext("/token", this::token);
        server.createContext("/mcp", ex -> {
            String a = ex.getRequestHeaders().getFirst("Authorization");
            synchronized (mcpAuthHeaders) {
                mcpAuthHeaders.add(a);
            }
            if (!("Bearer " + validAccess).equals(a)) {
                json(ex, 401, "{}");
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"initialize\"")) json(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
            else json(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}");
        });
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> f = new HashMap<>();
        for (String p : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            int i = p.indexOf('=');
            if (i > 0) f.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        synchronized (tokenRequests) {
            tokenRequests.add(f);
        }
        String grant = f.get("grant_type");
        if (grant.endsWith("device_code")) {
            String err = devicePolls.poll();
            if (err != null) {
                json(ex, 400, "{\"error\":\"" + err + "\"}");
                return;
            }
        }
        if (grant.equals("refresh_token") && refreshError != null) {
            json(ex, 400, "{\"error\":\"" + refreshError + "\",\"error_description\":\"revoked\"}");
            return;
        }
        int n = issued.incrementAndGet();
        validAccess = "access-" + n;
        json(ex, 200, "{\"access_token\":\"access-" + n + "\",\"refresh_token\":\"refresh-" + n + "\",\"expires_in\":" + expiresIn
                + ",\"token_type\":\"Bearer\",\"scope\":\"x\"}");
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
