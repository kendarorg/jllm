package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.web.commons.Gate;
import org.kendar.llm.web.commons.WebAuthentication;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the {@link ApiInterceptor} on {@code /api/**} and the {@link AdminInterceptor} on {@code /api/admin/**}. */
class WebConfig implements WebMvcConfigurer {

    private final ApiInterceptor api;
    private final AdminInterceptor admin;

    WebConfig(Gate gate, WebAuthentication authentication, ObjectMapper mapper, boolean enforceOnLoopback,
              boolean boundToLoopback) {
        this.api = new ApiInterceptor(gate, authentication, mapper, enforceOnLoopback, boundToLoopback);
        this.admin = new AdminInterceptor(gate, authentication, mapper);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(api).addPathPatterns("/api/**");
        registry.addInterceptor(admin).addPathPatterns("/api/admin/**");
    }
}
