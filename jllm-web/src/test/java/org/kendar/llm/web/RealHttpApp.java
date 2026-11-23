package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.io.File;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Test support: the whole application on a random port over SQLite in a temp dir, and a real HTTP client. */
final class RealHttpApp {

    static final ObjectMapper JSON = new ObjectMapper();

    private RealHttpApp() {
    }

    static String slashes(Path p) {
        return p.toString().replace(File.separatorChar, '/');
    }

    /** SQL store, shared {@code <home>/jllm.db}, own workdir (the history root lock of a workdir lives until the process ends). */
    static ConfigurableApplicationContext start(Path home, String work, String... extra) {
        return start(home, work, null, extra);
    }

    /** As above, with environment variables ({@code JLLM_TOKEN_KEY}) read from {@code env} instead of the process. */
    static ConfigurableApplicationContext start(Path home, String work, Function<String, String> env, String... extra) {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1", "--jllm.provider=ollama",
                "--jllm.workdir=" + slashes(home.resolve(work)),
                "--jllm.sql.url=jdbc:sqlite:" + slashes(home.resolve("jllm.db")), "--spring.main.banner-mode=off"));
        args.addAll(List.of(extra));
        if (args.stream().noneMatch(a -> a.startsWith("--jllm.store="))) args.add("--jllm.store=sql"); // a repeated flag would be a list
        SpringApplicationBuilder b = new SpringApplicationBuilder(JllmWebApplication.class).web(WebApplicationType.SERVLET);
        if (env != null) {
            b.initializers(ctx -> ((GenericApplicationContext) ctx).registerBean("jllmSqlEnv", Function.class, () -> env));
        }
        return b.run(args.toArray(String[]::new));
    }

    /** A cookie-keeping HTTP client of one running context; {@code path} arguments are relative to its context path. */
    static final class Client {
        final HttpClient http;
        final String base;

        Client(ConfigurableApplicationContext c) {
            this(c, new CookieManager());
        }

        /** Cookies are kept per host, not per port: sharing the manager signs the same browser in on several instances. */
        Client(ConfigurableApplicationContext c, CookieManager cookies) {
            http = HttpClient.newBuilder().cookieHandler(cookies).build();
            String ctx = c.getEnvironment().getProperty("server.servlet.context-path", "");
            base = "http://localhost:" + c.getEnvironment().getProperty("local.server.port") + ctx;
        }

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        int login(String id, String password) throws Exception {
            return send("POST", "/api/login", "{\"id\":\"" + id + "\",\"password\":\"" + password + "\"}").statusCode();
        }

        String createChat() throws Exception {
            HttpResponse<String> r = send("POST", "/api/chat", "{}");
            if (r.statusCode() != 200) throw new AssertionError("create chat: " + r.statusCode() + " " + r.body());
            return JSON.readTree(r.body()).path("chatId").asText();
        }

        boolean listed(String chatId) throws Exception {
            for (JsonNode n : JSON.readTree(send("GET", "/api/chat", null).body())) {
                if (chatId.equals(n.path("chatId").asText())) return true;
            }
            return false;
        }
    }
}
