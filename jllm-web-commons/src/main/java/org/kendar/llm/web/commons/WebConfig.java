package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the {@link ApiInterceptor} on {@code /api/**}. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiInterceptor interceptor;

    public WebConfig(WebAuthentication authentication, ChatAccess access, AgentHarness harness, ObjectMapper mapper) {
        this.interceptor = new ApiInterceptor(authentication, access, harness.sessions(), mapper);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
