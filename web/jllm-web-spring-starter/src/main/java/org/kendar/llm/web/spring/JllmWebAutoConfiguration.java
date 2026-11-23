package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.identity.ChatAccess;
import org.kendar.llm.harness.identity.HarnessIdentity;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.plugin.ServiceRegistry;
import org.kendar.llm.harness.plugin.WebPluginHost;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.settings.ProjectMcpTrust;
import org.kendar.llm.harness.settings.SettingsSource;
import org.kendar.llm.harness.state.StateStore;
import org.kendar.llm.web.commons.ChatApi;
import org.kendar.llm.web.commons.FilesApi;
import org.kendar.llm.web.commons.Gate;
import org.kendar.llm.web.commons.HarnessFactory;
import org.kendar.llm.web.commons.JllmProperties;
import org.kendar.llm.web.commons.OAuthApi;
import org.kendar.llm.web.commons.WebAuthentication;
import org.kendar.llm.web.commons.admin.AdminApi;
import org.kendar.llm.web.commons.auth.AuthApi;
import org.kendar.llm.web.commons.auth.OidcApi;
import org.kendar.llm.web.commons.auth.OidcProperties;
import org.kendar.llm.web.commons.me.MeApi;
import org.kendar.llm.web.commons.plugins.PluginApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The web API over the harness: the chat, file, OAuth and plugin endpoints, the {@code /api/**} gate. The application
 * supplies {@link WebAuthentication}, {@link ChatAccess} and {@link HarnessIdentity}; without them nobody signs in, no
 * chat is visible and no tool is allowed. When it supplies {@link AuthApi}, {@link OidcApi}, {@link MeApi} and
 * {@link AdminApi} beans, their endpoints are mounted too.
 */
@AutoConfiguration
public class JllmWebAutoConfiguration {

    // ── settings ──────────────────────────────────────────────────────────────

    /** {@code jllm.*}. */
    @Bean
    public JllmProperties jllmProperties(Environment env) {
        return Binder.get(env).bind("jllm", JllmProperties.class).orElseGet(JllmProperties::defaults);
    }

    /** {@code jllm.auth.*}. */
    @Bean
    public OidcProperties oidcProperties(Environment env) {
        return Binder.get(env).bind("jllm.auth", OidcProperties.class).orElseGet(() -> new OidcProperties(null, null));
    }

    // ── harness ───────────────────────────────────────────────────────────────

    @Bean
    public ModelClient modelClient(JllmProperties p, ObjectMapper mapper) {
        return HarnessFactory.modelClient(p, mapper);
    }

    @Bean
    public HarnessPricing harnessPricing(JllmProperties p) {
        return HarnessFactory.pricing(p);
    }

    @Bean
    public ServiceRegistry serviceRegistry() {
        return new ServiceRegistry();
    }

    /**
     * The harness. A bean of the application for the settings source, the project trust, the chat, sub-agent, mnemonic
     * and state stores and the plugin storage replaces the file-based default.
     */
    @Bean
    public AgentHarness agentHarness(JllmProperties p, ModelClient modelClient, HarnessPricing pricing,
                                     ObjectMapper mapper, HarnessIdentity identity,
                                     ObjectProvider<SettingsSource> settingsSource,
                                     ObjectProvider<ProjectMcpTrust> projectTrust,
                                     ObjectProvider<ChatSessionStore> sessionStore,
                                     ObjectProvider<SubAgentRunLog> subAgentRunLog,
                                     ObjectProvider<MnemonicStore> mnemonicStore,
                                     ObjectProvider<StateStore> stateStore,
                                     ServiceRegistry services,
                                     ObjectProvider<PluginStorages> pluginStorages) {
        return HarnessFactory.harness(p, modelClient, pricing, mapper, identity, settingsSource.getIfAvailable(),
                projectTrust.getIfAvailable(), sessionStore.getIfAvailable(), subAgentRunLog.getIfAvailable(),
                mnemonicStore.getIfAvailable(), stateStore.getIfAvailable(), services, pluginStorages.getIfAvailable());
    }

    /** Loads the web plugins; after the harness, whose plugins they may need. */
    @Bean(destroyMethod = "close")
    public WebPluginHost webPluginHost(AgentHarness harness, JllmProperties p) {
        return HarnessFactory.webPluginHost(harness, p);
    }

    @Bean
    SandboxSweep sandboxSweep(AgentHarness harness) {
        return new SandboxSweep(harness);
    }

    // ── defaults the application replaces ─────────────────────────────────────

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

    // ── gate and handlers ─────────────────────────────────────────────────────

    @Bean
    public Gate gate(WebAuthentication authentication, ChatAccess access, AgentHarness harness) {
        return new Gate(authentication, access, harness.sessions());
    }

    @Bean
    WebConfig jllmWebConfig(Gate gate, WebAuthentication authentication, ObjectMapper mapper, JllmProperties p) {
        return new WebConfig(gate, authentication, mapper, p.web() != null && p.web().enforceOnLoopback());
    }

    @Bean
    SecurityHeadersFilter jllmSecurityHeadersFilter(JllmProperties p) {
        return new SecurityHeadersFilter(p.web() == null ? null : p.web().contentSecurityPolicy());
    }

    @Bean
    ApiExceptionAdvice apiExceptionAdvice() {
        return new ApiExceptionAdvice();
    }

    @Bean
    public ChatApi chatApi(AgentHarness harness, ObjectMapper mapper, HarnessPricing pricing, ChatAccess access) {
        return new ChatApi(harness, mapper, pricing, access);
    }

    @Bean
    public FilesApi filesApi(AgentHarness harness) {
        return new FilesApi(harness);
    }

    /** Refuses to start when a per-user source cannot work (see {@link OAuthApi#check}). */
    @Bean(initMethod = "check")
    public OAuthApi oAuthApi(AgentHarness harness, JllmProperties p) {
        return new OAuthApi(harness, p);
    }

    @Bean
    public PluginApi pluginApi(WebPluginHost host) {
        return new PluginApi(host);
    }

    @Bean
    public ChatController chatController(ChatApi chat, WebAuthentication authentication) {
        return new ChatController(chat, authentication);
    }

    @Bean
    public FilesController filesController(FilesApi files) {
        return new FilesController(files);
    }

    @Bean
    public OAuthController oAuthController(OAuthApi oauth, WebAuthentication authentication) {
        return new OAuthController(oauth, authentication);
    }

    @Bean
    public PluginController pluginController(PluginApi plugins) {
        return new PluginController(plugins);
    }

    // ── sign-in, own settings and administration: when the application supplies them ──

    @Configuration(proxyBeanMethods = false)
    static class Identity {

        @Bean
        @ConditionalOnBean(AuthApi.class)
        AuthController authController(AuthApi api) {
            return new AuthController(api);
        }

        @Bean
        @ConditionalOnBean(OidcApi.class)
        OidcController oidcController(OidcApi api) {
            return new OidcController(api);
        }

        @Bean
        @ConditionalOnBean(MeApi.class)
        MeController meController(MeApi api) {
            return new MeController(api);
        }

        @Bean
        @ConditionalOnBean(AdminApi.class)
        AdminController adminController(AdminApi api) {
            return new AdminController(api);
        }
    }
}
