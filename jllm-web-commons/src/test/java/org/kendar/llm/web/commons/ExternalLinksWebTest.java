package org.kendar.llm.web.commons;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.settings.HarnessConfigMapper;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.HarnessSettingsLoader;
import org.kendar.llm.harness.tools.ExternalLinks;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The web host ignores {@code files.externalLinks}: it always uses {@code none} and warns when the key says otherwise. */
class ExternalLinksWebTest {

    @TempDir
    Path dir;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(HarnessConfiguration.class);

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
    void readwriteInTheSettingsIsIgnoredAndWarnedOnce() throws Exception {
        HarnessSettings forced = HarnessConfiguration.withoutExternalLinks(load("{\"files\":{\"externalLinks\":\"readwrite\"}}"));

        assertEquals(ExternalLinks.NONE, forced.externalLinks());
        assertEquals(ExternalLinks.NONE, HarnessConfigMapper.apply(forced, HarnessConfig.defaults()).externalLinks());
        assertEquals(1, warnings().size());
        String msg = warnings().get(0).getFormattedMessage();
        assertTrue(msg.contains("files.externalLinks=readwrite") && msg.contains("ignored"), msg);
    }

    @Test
    void readInTheSettingsIsIgnoredToo() throws Exception {
        assertEquals(ExternalLinks.NONE,
                HarnessConfiguration.withoutExternalLinks(load("{\"files\":{\"externalLinks\":\"read\"}}")).externalLinks());
        assertEquals(1, warnings().size());
    }

    @Test
    void noKeyOrNoneMeansNoWarning() throws Exception {
        assertEquals(ExternalLinks.NONE, HarnessConfiguration.withoutExternalLinks(load("{}")).externalLinks());
        assertEquals(ExternalLinks.NONE, HarnessConfiguration.withoutExternalLinks(HarnessSettings.empty()).externalLinks());
        assertEquals(ExternalLinks.NONE,
                HarnessConfiguration.withoutExternalLinks(load("{\"files\":{\"externalLinks\":\"none\"}}")).externalLinks());
        assertEquals(0, warnings().size());
    }

    @Test
    void otherFileLimitsAreKept() throws Exception {
        HarnessSettings forced = HarnessConfiguration.withoutExternalLinks(
                load("{\"files\":{\"maxReadBytes\":2000,\"externalLinks\":\"readwrite\"}}"));
        assertEquals(2000, forced.filesMaxReadBytes());
    }
}
