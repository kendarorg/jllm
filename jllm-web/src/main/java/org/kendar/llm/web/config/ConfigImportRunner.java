package org.kendar.llm.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.settings.ConfigProvider;
import org.kendar.llm.harness.settings.VirtualConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * {@code jllm-web --import-config <dir>}: imports the file configuration once into the provider in use
 * ({@link ConfigImporter}); {@code JllmWebApplication} then ends the process.
 */
@Component
@ConditionalOnProperty(name = ConfigImportRunner.PROPERTY)
public class ConfigImportRunner implements ApplicationRunner {

    /** Set by the command line flag. */
    public static final String PROPERTY = "jllm.import-config";
    private static final Logger LOG = LoggerFactory.getLogger(ConfigImportRunner.class);

    private final ConfigProvider provider;
    private final ObjectMapper mapper;
    private final org.springframework.core.env.Environment env;

    public ConfigImportRunner(ConfigProvider provider, ObjectMapper mapper, org.springframework.core.env.Environment env) {
        this.provider = provider;
        this.mapper = mapper;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!(provider instanceof VirtualConfigProvider virtual)) {
            throw new IllegalStateException("No virtual configuration provider to import into");
        }
        Path dir = Path.of(env.getRequiredProperty(PROPERTY));
        var result = ConfigImporter.importDir(dir, virtual, mapper);
        LOG.info("Imported the configuration of {} into {}: {}", dir.toAbsolutePath().normalize(), virtual.describe(), result);
    }
}
