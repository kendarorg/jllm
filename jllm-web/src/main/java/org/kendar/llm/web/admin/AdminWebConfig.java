package org.kendar.llm.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.web.commons.WebAuthentication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the {@link AdminInterceptor} on {@code /api/admin/**}. */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminInterceptor interceptor;

    public AdminWebConfig(WebAuthentication authentication, ObjectMapper mapper) {
        this.interceptor = new AdminInterceptor(authentication, mapper);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/admin/**");
    }
}
