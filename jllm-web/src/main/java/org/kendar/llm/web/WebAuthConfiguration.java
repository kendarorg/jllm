package org.kendar.llm.web;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.auth.CredentialResolver;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.TokenStore;
import org.kendar.llm.harness.auth.UserPolicyResolver;
import org.kendar.llm.web.commons.admin.ToolCatalogView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.web.commons.auth.CookieAuthentication;
import org.kendar.llm.web.commons.auth.OidcClient;
import org.kendar.llm.web.commons.admin.AdminApi;
import org.kendar.llm.web.commons.auth.AuthApi;
import org.kendar.llm.web.commons.auth.OidcApi;
import org.kendar.llm.web.commons.me.MeApi;
import org.kendar.llm.web.commons.auth.OidcProperties;
import org.kendar.llm.web.commons.JllmProperties;
import org.kendar.llm.web.commons.auth.LoginThrottle;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.HarnessIdentity;
import org.kendar.llm.harness.identity.RoleRepository;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.web.commons.users.Permissions;
import org.kendar.llm.web.commons.users.UserCredentialResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Sign-in, users, roles and skills of the reference implementation, over whichever {@link UserRepository},
 * {@link RoleRepository}, {@link SkillRepository} and {@link LoginSessionRepository} beans exist: SQL ones from the
 * SQL starter, else the file ones of {@link FileStoresAutoConfiguration}. Supplies the authentication and
 * authorization the common web API needs.
 */
@Configuration
public class WebAuthConfiguration {

    private static Path root(String webauthDir) {
        return Path.of(webauthDir).toAbsolutePath().normalize();
    }

    @Bean
    public Permissions permissions(RoleRepository roles) {
        return new Permissions(roles);
    }

    /** Random passwords instead of the default ones when {@code jllm.web.use-real-password=true}. */
    @Bean
    public RealPasswordInitializer realPasswordInitializer(UserRepository users, JllmProperties jllm) {
        return new RealPasswordInitializer(users, jllm);
    }

    @Bean
    public LoginThrottle loginThrottle() {
        return new LoginThrottle();
    }

    /**
     * The OpenID Connect client. With {@code jllm.auth.mode} {@code oidc} or {@code both} the settings are checked and
     * the provider's discovery document is read at startup; a mistake stops the server naming the key.
     */
    @Bean
    public OidcClient oidcClient(OidcProperties auth, JllmProperties jllm, Permissions permissions, ObjectMapper mapper) {
        String publicUrl = jllm.web() == null ? null : jllm.web().publicUrl();
        auth.validate(publicUrl, System::getenv, role -> permissions.role(role).isPresent());
        OidcClient client = new OidcClient(auth, publicUrl, System::getenv, mapper);
        if (auth.oidcEnabled()) {
            try {
                client.discover();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("jllm.auth.oidc.issuer: discovery failed: " + e.getMessage(), e);
            }
        }
        return client;
    }

    @Bean
    public OidcApi oidcApi(OidcProperties auth, OidcClient client, UserRepository users,
                           LoginSessionRepository sessions, LoginThrottle throttle) {
        return new OidcApi(auth, client, users, sessions, throttle);
    }

    @Bean
    public AuthApi authApi(UserRepository users, CookieAuthentication authentication, LoginSessionRepository sessions,
                           LoginThrottle throttle, OidcProperties auth) {
        return new AuthApi(users, authentication, sessions, throttle, auth);
    }

    @Bean
    public MeApi meApi(UserRepository users, Permissions permissions, SkillRepository skills,
                       LoginSessionRepository sessions, LoginThrottle throttle, AgentHarness harness,
                       ToolCatalogView catalog) {
        return new MeApi(users, permissions, skills, sessions, throttle, harness, catalog);
    }

    @Bean
    public AdminApi adminApi(UserRepository users, Permissions permissions, SkillRepository skills,
                             LoginSessionRepository sessions, AgentHarness harness, ToolCatalogView catalog) {
        return new AdminApi(users, permissions, skills, sessions, harness, catalog);
    }

    /** Both the {@code WebAuthentication} and the {@code ChatAccess} of the web API. */
    @Bean
    public CookieAuthentication cookieAuthentication(UserRepository users, LoginSessionRepository sessions) {
        return new CookieAuthentication(users, sessions);
    }

    @Bean
    public ToolCatalogView toolCatalogView(AgentHarness harness) {
        return new ToolCatalogView(harness);
    }

    /** A {@link TokenStore} bean (e.g. a database) replaces the token files. */
    @Bean
    public HarnessIdentity harnessIdentity(UserRepository users, Permissions permissions, SkillRepository skills,
                                           ObjectProvider<TokenStore> tokenStoreBean,
                                           @Value("${jllm.webauth-dir:webauth}") String webauthDir) {
        Path tokens = root(webauthDir).resolve("tokens");
        TokenStore tokenStore = tokenStoreBean.getIfAvailable();
        CredentialResolver credentials = new UserCredentialResolver(users);
        UserPolicyResolver policies = permissions.resolver(users, skills);
        Function<String, HarnessPrincipal> principals = id -> users.find(id).map(User::principal).orElse(null);
        Function<String, org.kendar.llm.harness.identity.RoleSandbox> sandboxes = id -> permissions.sandboxOf(users, id);
        return new HarnessIdentity() {
            @Override
            public Function<String, org.kendar.llm.harness.identity.RoleSandbox> sandboxes() {
                return sandboxes;
            }

            @Override
            public CredentialResolver credentials() {
                return credentials;
            }

            @Override
            public UserPolicyResolver policies() {
                return policies;
            }

            @Override
            public Function<String, HarnessPrincipal> principals() {
                return principals;
            }

            /** {@code <webauth>/tokens}: one encrypted file per user. */
            @Override
            public Path tokenDir() {
                return tokens;
            }

            @Override
            public TokenStore tokenStore() {
                return tokenStore;
            }
        };
    }
}
