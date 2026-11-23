package org.kendar.llm.harness.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** {@code web_download} writes through the jail: a directory swapped for a link right before the write is not followed. */
class WebDownloadJailTest {

    @TempDir
    Path tmp;

    HttpServer server;
    Path root;
    Path outside;
    ToolContext ctx;
    WebAgentTools web;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("work"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "PAYLOAD".getBytes(StandardCharsets.UTF_8);
        server.createContext("/file", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        web = new WebAgentTools(config(tmp), MAPPER);
        ctx = new ToolContext("c1", root);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        JailTestSupport.reset();
    }

    static java.util.stream.Stream<PathJail.Strategy> strategies() {
        return JailTestSupport.strategies();
    }

    private String download(String path) throws Exception {
        return web.tools().stream().filter(t -> t.name().equals("web_download")).findFirst().orElseThrow()
                .execute(ctx, json("{'url':'" + base + "/file','path':'" + path + "'}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void downloadWorksThroughTheJail(PathJail.Strategy s) throws Exception {
        PathJail.forced = s;

        String out = download("deep/dir/got.bin");

        assertTrue(out.contains("Saved 7 bytes"), out);
        assertEquals("PAYLOAD", Files.readString(root.resolve("deep/dir/got.bin")));
        assertEquals(List.of("deep/dir/got.bin"), JailTestSupport.files(root));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void aDirectorySwappedForALinkIsNotWrittenInto(PathJail.Strategy s) throws Exception {
        PathJail.forced = s;
        assumeTrue(JailTestSupport.canLinkDirs(tmp), "links are not permitted on this machine");
        for (int stage = 1; stage <= 2; stage++) { // the temp file create, then the rename over the target
            String d = "sub" + stage;
            Files.createDirectories(root.resolve(d));
            Files.writeString(outside.resolve("got.bin"), "TOP SECRET");
            List<String> before = JailTestSupport.files(outside);
            JailTestSupport.onNth(stage, () -> JailTestSupport.swapDirForLink(root.resolve(d), outside));

            try {
                download(d + "/got.bin");
            } catch (Exception e) {
                // refused: fine
            }

            assertEquals(before, JailTestSupport.files(outside), "stage " + stage);
            assertEquals("TOP SECRET", Files.readString(outside.resolve("got.bin")), "stage " + stage);
        }
    }
    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void downloadThroughAnExternalLinkIsRefusedUnlessReadWrite(PathJail.Strategy s) throws Exception {
        PathJail.forced = s;
        assumeTrue(JailTestSupport.canLinkDirs(tmp), "links are not permitted on this machine");
        assertTrue(JailTestSupport.linkDir(root.resolve("ext"), outside));

        for (ExternalLinks mode : new ExternalLinks[]{ExternalLinks.NONE, ExternalLinks.READ}) {
            ctx = new ToolContext("c1", root).withLinks(mode);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> download("ext/got.bin"));
            assertTrue(e.getMessage().contains(mode == ExternalLinks.NONE ? "is a link that leaves the work dir"
                    : "files.externalLinks is read"), e.getMessage());
            assertFalse(Files.exists(outside.resolve("got.bin")), mode.name());
        }

        ctx = new ToolContext("c1", root).withLinks(ExternalLinks.READ_WRITE);
        assertTrue(download("ext/got.bin").contains("Saved 7 bytes"));
        assertEquals("PAYLOAD", Files.readString(outside.resolve("got.bin")));
    }
}
