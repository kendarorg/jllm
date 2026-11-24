package org.kendar.llm.web.spring;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class SecurityHeadersFilterTest {

    private static MockHttpServletResponse run(String configured, String uri, String preset) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        if (preset != null) res.setHeader("Content-Security-Policy", preset);
        new SecurityHeadersFilter(configured).doFilter(new MockHttpServletRequest("GET", uri), res,
                new MockFilterChain());
        return res;
    }

    @Test
    void defaultsOnIndexAndApi() throws Exception {
        for (String uri : new String[]{"/", "/index.html", "/api/chat"}) {
            for (String cfg : new String[]{null, "", "  "}) {
                MockHttpServletResponse r = run(cfg, uri, null);
                String csp = r.getHeader("Content-Security-Policy");
                assertTrue(csp.startsWith("default-src 'self'"), uri);
                assertTrue(csp.contains("frame-ancestors 'none'"));
                assertTrue(csp.contains("base-uri 'self'"));
                assertTrue(csp.contains("form-action 'self'"));
                assertEquals("nosniff", r.getHeader("X-Content-Type-Options"));
                assertEquals("no-referrer", r.getHeader("Referrer-Policy"));
            }
        }
    }

    @Test
    void propertyOverrides() throws Exception {
        assertEquals("default-src 'none'", run("default-src 'none'", "/", null).getHeader("Content-Security-Policy"));
    }

    @Test
    void offDisablesCspOnly() throws Exception {
        MockHttpServletResponse r = run("off", "/", null);
        assertNull(r.getHeader("Content-Security-Policy"));
        assertEquals("nosniff", r.getHeader("X-Content-Type-Options"));
    }

    @Test
    void existingHeaderKept() throws Exception {
        assertEquals("x", run(null, "/", "x").getHeader("Content-Security-Policy"));
    }
}
