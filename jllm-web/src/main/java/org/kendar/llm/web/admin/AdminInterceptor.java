package org.kendar.llm.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.kendar.llm.web.commons.WebAuthentication;
import org.kendar.llm.web.commons.WebPrincipal;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /api/admin/**}: 401 without a signed-in user, 403 unless the user is an admin. Looks the user up itself, so
 * it does not depend on running after the common {@code /api/**} gate.
 */
public class AdminInterceptor implements HandlerInterceptor {

    private final WebAuthentication authentication;
    private final ObjectMapper mapper;

    public AdminInterceptor(WebAuthentication authentication, ObjectMapper mapper) {
        this.authentication = authentication;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Optional<WebPrincipal> user = authentication.current(request);
        if (user.isEmpty()) return reject(response, 401, "Sign in first");
        if (!user.get().admin()) return reject(response, 403, "Admins only");
        request.setAttribute(WebPrincipal.ATTR, user.get());
        return true;
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(mapper.writeValueAsString(Map.of("error", message)));
        return false;
    }
}
