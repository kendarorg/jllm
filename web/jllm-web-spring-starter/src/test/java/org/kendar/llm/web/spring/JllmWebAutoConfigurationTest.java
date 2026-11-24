package org.kendar.llm.web.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.identity.ChatAccess;
import org.kendar.llm.harness.identity.HarnessIdentity;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.ChatApi;
import org.kendar.llm.web.commons.Cookies;
import org.kendar.llm.web.commons.Gate;
import org.kendar.llm.web.commons.JllmProperties;
import org.kendar.llm.web.commons.WebAuthentication;
import org.kendar.llm.web.commons.auth.OidcProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The auto-configuration alone: the app boots with no bean of its own. */
class JllmWebAutoConfigurationTest {

    @TempDir
    Path dir;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, JllmWebAutoConfiguration.class))
                .withPropertyValues("jllm.provider=ollama", "jllm.workdir=" + dir.resolve("work"),
                        "jllm.max-tokens=1234");
    }

    @Test
    void bootsWithNoBeanOfItsOwnAndNobodyCanSignIn() {
        runner().run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertNotNull(ctx.getBean(AgentHarness.class));
            assertNotNull(ctx.getBean(ChatController.class));
            assertNotNull(ctx.getBean(FilesController.class));
            assertNotNull(ctx.getBean(OAuthController.class));
            assertNotNull(ctx.getBean(PluginController.class));
            assertNotNull(ctx.getBean(ChatApi.class));
            assertSame(WebAuthentication.NONE, ctx.getBean(WebAuthentication.class));
            assertSame(ChatAccess.NONE, ctx.getBean(ChatAccess.class));
            assertSame(HarnessIdentity.NONE, ctx.getBean(HarnessIdentity.class));
            Gate gate = ctx.getBean(Gate.class);
            assertEquals(401, gate.decide("/api/chat", null).status());
            assertEquals(0, ctx.getBeansOfType(AuthController.class).size());
            assertEquals(0, ctx.getBeansOfType(AdminController.class).size());
        });
    }

    @Test
    void thePropertiesAreBound() {
        runner().withPropertyValues("jllm.web.public-url=https://x.example", "jllm.auth.mode=both",
                "jllm.plugins.js.enabled=true").run(ctx -> {
            JllmProperties p = ctx.getBean(JllmProperties.class);
            assertEquals("ollama", p.provider());
            assertEquals(1234, p.maxTokens());
            assertEquals("https://x.example", p.web().publicUrl());
            assertEquals(true, p.plugins().get("js").enabled());
            assertEquals("both", ctx.getBean(OidcProperties.class).mode());
        });
    }

    @Test
    void theApplicationsOwnBeansReplaceTheDefaults() {
        WebAuthentication mine = new WebAuthentication() {
            @Override
            public Optional<Principal> current(Cookies cookies) {
                return Optional.empty();
            }

            @Override
            public Set<String> openPaths() {
                return Set.of("/api/mine");
            }
        };
        runner().withBean(WebAuthentication.class, () -> mine).run(ctx -> {
            assertSame(mine, ctx.getBean(WebAuthentication.class));
            assertEquals(0, ctx.getBean(Gate.class).decide("/api/mine", null).status());
        });
    }
}
