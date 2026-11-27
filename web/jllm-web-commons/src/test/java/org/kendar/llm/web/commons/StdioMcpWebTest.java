package org.kendar.llm.web.commons;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.HarnessSettingsLoader;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The web host never starts a stdio MCP server: each one is dropped with a warning, HTTP servers are kept. */
class StdioMcpWebTest {

    @TempDir
    Path dir;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(HarnessFactory.class);

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    private HarnessSettings load(String json) throws Exception {
        return HarnessSettingsLoader.load(Files.writeString(dir.resolve("s.json"), json));
    }

    private List<ILoggingEvent> warnings() {
        return logged.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void stdioServersAreDroppedAndWarnedHttpKept() throws Exception {
        HarnessSettings s = HarnessFactory.withoutStdioMcpServers(load("""
                {"mcpServers":[
                  {"name":"local","command":"python3","args":["server.py"]},
                  {"name":"remote","url":"http://localhost:9000/mcp"},
                  {"name":"other","command":"node"}
                ]}"""));

        assertEquals(List.of("remote"), s.mcpServers().stream().map(McpServerSettings::name).toList());
        assertEquals(2, warnings().size());
        String msg = warnings().get(0).getFormattedMessage();
        assertTrue(msg.contains("'local'") && msg.contains("ignored") && msg.contains("stdio"), msg);
    }

    @Test
    void noStdioServerMeansSameSettingsAndNoWarning() throws Exception {
        HarnessSettings http = load("{\"mcpServers\":[{\"name\":\"remote\",\"url\":\"http://localhost:9000/mcp\"}]}");
        assertSame(http, HarnessFactory.withoutStdioMcpServers(http));
        HarnessSettings empty = HarnessSettings.empty();
        assertSame(empty, HarnessFactory.withoutStdioMcpServers(empty));
        assertEquals(0, warnings().size());
    }
}
