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
        SandboxSweep.class})
public class JllmWebCommonsAutoConfiguration {

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
