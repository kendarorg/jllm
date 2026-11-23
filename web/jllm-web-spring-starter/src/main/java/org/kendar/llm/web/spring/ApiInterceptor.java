package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.kendar.llm.harness.identity.Principal;
import org.kendar.llm.web.commons.Gate;
import org.kendar.llm.web.commons.WebAuthentication;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;

/** Applies the {@link Gate} to {@code /api/**}; stores the signed-in user as {@link Principal#ATTR}. */
public class ApiInterceptor implements HandlerInterceptor {

    private final Gate gate;
    private final WebAuthentication authentication;
    private final ObjectMapper mapper;

    public ApiInterceptor(Gate gate, WebAuthentication authentication, ObjectMapper mapper) {
        this.gate = gate;
        this.authentication = authentication;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = request.getRequestURI();
        Principal user = null;
        if (!gate.isOpen(path)) {
            user = authentication.current(Requests.cookies(request)).orElse(null);
            if (user != null) request.setAttribute(Principal.ATTR, user);
        }
        return Verdicts.apply(gate.decide(path, user), response, mapper);
    }

    /** Writes a refusal in the shape of the API: {@code {"error": ...}}. */
    static final class Verdicts {
        private Verdicts() {
        }

        static boolean apply(Gate.Decision decision, HttpServletResponse response, ObjectMapper mapper)
                throws IOException {
            if (decision.allowed()) return true;
            response.setStatus(decision.status());
            response.setContentType("application/json");
            response.getWriter().write(mapper.writeValueAsString(Map.of("error", decision.message())));
            return false;
        }
    }
}
