package org.kendar.llm.harness.tools;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** A real HTTPS server with a self-signed certificate (made with the JDK keytool). */
class WebInvalidCertificateTest {

    @TempDir
    Path tmp;

    HttpsServer server;
    String base;
    HarnessConfig config;
    WebAgentTools web;

    @BeforeEach
    void setUp() throws Exception {
        Path store = tmp.resolve("selfsigned.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "t", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-dname", "CN=not-the-host", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        try (InputStream in = p.getInputStream()) {
            in.readAllBytes();
        }
        assertEquals(0, p.waitFor());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/plain", ex -> {
            byte[] b = "secure text".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "https://127.0.0.1:" + server.getAddress().getPort();
        config = config(tmp);
        web = new WebAgentTools(config, MAPPER);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private HarnessTool tool(String name) {
        return web.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    /** An approver that allows the host and records what it was asked about the certificate. */
    private static ToolContext ctx(Path dir, List<String> asked, boolean allowInsecure) {
        return new ToolContext("c1", dir).withHosts(new HostApprover() {
            @Override
            public boolean approve(String host, String url) {
                return true;
            }

            @Override
            public boolean approveInsecure(String host, String url, String reason) {
                asked.add(host + " | " + reason);
                return allowInsecure;
            }
        });
    }

    @Test
    void aSelfSignedCertificateAsksAndTheCallGoesOnWhenAllowed() throws Exception {
        List<String> asked = new ArrayList<>();

        String out = tool("web_fetch").execute(ctx(tmp, asked, true), json("{'url':'" + base + "/plain'}"));

        assertEquals("HTTP 200\nsecure text", out);
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).startsWith("127.0.0.1 | "), asked.toString());
        assertTrue(asked.get(0).length() > "127.0.0.1 | ".length(), "a reason is given");
    }

    @Test
    void aRefusalFailsTheCallAndSaysWhy() throws Exception {
        List<String> asked = new ArrayList<>();

        Exception e = assertThrows(Exception.class,
                () -> tool("web_fetch").execute(ctx(tmp, asked, false), json("{'url':'" + base + "/plain'}")));

        assertTrue(e.getMessage().contains("certificate") && e.getMessage().contains("127.0.0.1"), e.getMessage());
        assertEquals(1, asked.size());
    }

    @Test
    void theAnswerCoversOnlyThatCall() throws Exception {
        List<String> asked = new ArrayList<>();
        ToolContext c = ctx(tmp, asked, true);

        tool("web_fetch").execute(c, json("{'url':'" + base + "/plain'}"));
        tool("web_fetch").execute(c, json("{'url':'" + base + "/plain'}"));

        assertEquals(2, asked.size(), "asked again on the next call");
    }

    @Test
    void withoutAnApproverAnInvalidCertificateIsRefused() {
        Exception e = assertThrows(Exception.class,
                () -> tool("web_fetch").execute(new ToolContext("c1", tmp), json("{'url':'" + base + "/plain'}")));

        assertTrue(e.getMessage().contains("certificate"), e.getMessage());
    }

    @Test
    void downloadAsksToo() throws Exception {
        List<String> asked = new ArrayList<>();

        tool("web_download").execute(ctx(tmp, asked, true), json("{'url':'" + base + "/plain','path':'x.txt'}"));

        assertEquals("secure text", Files.readString(tmp.resolve("x.txt")));
        assertEquals(1, asked.size());
    }

    @Test
    void networkErrorsAreNotCertificateProblems() {
        assertNull(WebAgentTools.certificateProblem(new java.net.ConnectException("refused")));
        assertNull(WebAgentTools.certificateProblem(new java.net.http.HttpTimeoutException("timeout")));
        assertNotNull(WebAgentTools.certificateProblem(new javax.net.ssl.SSLHandshakeException("bad")));
    }
}
