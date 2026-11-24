package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.Gate;
import org.kendar.llm.web.commons.WebAuthentication;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Applies {@link Gate#decideAdmin} to {@code /api/admin/**}. Looks the user up itself, so it does not depend on
 * running after the {@code /api/**} interceptor.
 */
public class AdminInterceptor implements HandlerInterceptor {

    private final Gate gate;
    private final WebAuthentication authentication;
    private final ObjectMapper mapper;

    public AdminInterceptor(Gate gate, WebAuthentication authentication, ObjectMapper mapper) {
        this.gate = gate;
        this.authentication = authentication;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Principal user = authentication.current(Requests.cookies(request)).orElse(null);
        if (!ApiInterceptor.Verdicts.apply(gate.decideAdmin(user), response, mapper)) return false;
        request.setAttribute(Principal.ATTR, user);
        return true;
    }
}
