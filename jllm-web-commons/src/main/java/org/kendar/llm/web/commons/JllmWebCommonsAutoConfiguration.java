package org.kendar.llm.web.commons;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The common web API: harness wiring, chat and file endpoints, the {@code /api/**} gate. The application supplies
 * {@link WebAuthentication}, {@link ChatAccess} and {@link HarnessIdentity}; without them nobody signs in, no chat is
 * visible and no tool is allowed.
 */
@AutoConfiguration
@EnableConfigurationProperties(JllmProperties.class)
@Import({HarnessConfiguration.class, WebConfig.class, ChatController.class, FilesController.class,
        SandboxSweep.class, OAuthController.class, org.kendar.llm.web.commons.plugins.WebPluginController.class})
public class JllmWebCommonsAutoConfiguration {

    @Bean
    public org.kendar.llm.harness.plugin.ServiceRegistry serviceRegistry() {
        return new org.kendar.llm.harness.plugin.ServiceRegistry();
    }

    /** Loads the web plugins listed and enabled in the harness settings' {@code plugins} section (properties win); after the harness, whose plugins they may need. */
    @Bean(destroyMethod = "close")
    public org.kendar.llm.web.commons.plugins.WebPluginHost webPluginHost(
            org.kendar.llm.harness.AgentHarness harness, JllmProperties p) {
        var storages = harness.services().lookup(org.kendar.llm.harness.plugin.PluginStorages.class).orElseThrow();
        var listed = harness.services().lookup(org.kendar.llm.web.commons.plugins.PluginsSettings.class)
                .map(org.kendar.llm.web.commons.plugins.PluginsSettings::plugins).orElse(null);
        var host = new org.kendar.llm.web.commons.plugins.WebPluginHost(harness.services(), storages, listed);
        String dir = JllmProperties.blankToNull(p.pluginDir());
        java.nio.file.Path pluginDir = dir != null ? java.nio.file.Path.of(dir)
                : java.nio.file.Files.isDirectory(java.nio.file.Path.of("plugins")) ? java.nio.file.Path.of("plugins") : null;
        host.load(org.kendar.llm.web.commons.plugins.WebPluginHost.discover(pluginDir));
        host.warnings().forEach(w -> org.slf4j.LoggerFactory.getLogger(org.kendar.llm.web.commons.plugins.WebPluginHost.class).warn(w));
        return host;
    }

    @Bean
    @ConditionalOnMissingBean
    public WebAuthentication webAuthentication() {
        return WebAuthentication.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatAccess chatAccess() {
        return ChatAccess.NONE;
    }

    @Bean
    @ConditionalOnMissingBean
    public HarnessIdentity harnessIdentity() {
        return HarnessIdentity.NONE;
    }
}
