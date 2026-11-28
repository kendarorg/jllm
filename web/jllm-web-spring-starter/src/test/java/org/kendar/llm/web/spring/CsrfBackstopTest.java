package org.kendar.llm.web.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code Origin} / {@code Sec-Fetch-Site} backstop to the SameSite cookie, for state-changing requests. */
class CsrfBackstopTest {

    private static ApiInterceptor interceptor(String publicUrl) {
        return new ApiInterceptor(null, null, new ObjectMapper(), false, true, publicUrl);
    }

    private static MockHttpServletRequest post(String host, String origin, String site) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/chat");
        if (host != null) r.addHeader("Host", host);
        if (origin != null) r.addHeader("Origin", origin);
        if (site != null) r.addHeader("Sec-Fetch-Site", site);
        return r;
    }

    @Test
    void aRequestWithoutBrowserHeadersIsNotACrossSiteBrowserRequest() {
        assertFalse(interceptor(null).crossSite(post("jllm.example.com", null, null)));
    }

    @Test
    void sameOriginPasses() {
        ApiInterceptor i = interceptor(null);
        assertFalse(i.crossSite(post("jllm.example.com", "https://jllm.example.com", "same-origin")));
        assertFalse(i.crossSite(post("localhost:8080", "http://localhost:8080", null)));
        assertFalse(i.crossSite(post("jllm.example.com", null, "none")), "a typed address or a bookmark");
        assertFalse(i.crossSite(post("JLLM.example.com", "https://jllm.EXAMPLE.com", null)), "host names ignore case");
    }

    @Test
    void anotherSiteIsRefusedByEitherHeader() {
        ApiInterceptor i = interceptor(null);
        assertTrue(i.crossSite(post("jllm.example.com", "https://evil.example.net", null)));
        assertTrue(i.crossSite(post("jllm.example.com", null, "cross-site")));
        assertTrue(i.crossSite(post("jllm.example.com", "https://jllm.example.com", "cross-site")), "the fetch metadata wins");
        assertTrue(i.crossSite(post("jllm.example.com", null, "same-site")), "a sibling sub-domain is not this origin");
        assertTrue(i.crossSite(post("jllm.example.com", "https://jllm.example.com.evil.net", null)));
        assertTrue(i.crossSite(post("jllm.example.com", "https://jllm.example.com:8443", null)), "another port is another origin");
    }

    @Test
    void anOpaqueOriginIsRefused() {
        ApiInterceptor i = interceptor(null);
        assertTrue(i.crossSite(post("jllm.example.com", "null", null)));
        assertTrue(i.crossSite(post("jllm.example.com", "not-an-origin", null)));
        assertTrue(i.crossSite(post(null, "https://jllm.example.com", null)), "no Host to compare with");
    }

    @Test
    void thePublicUrlIsAcceptedForAProxyThatRewritesTheHost() {
        ApiInterceptor i = interceptor("https://jllm.example.com/");
        assertFalse(i.crossSite(post("internal-host:8080", "https://jllm.example.com", null)));
        assertTrue(i.crossSite(post("internal-host:8080", "https://evil.example.net", null)));
        assertTrue(i.crossSite(post("internal-host:8080", "http://jllm.example.com", null)), "the scheme counts");
    }

    @Test
    void anUnusablePublicUrlAddsNothing() {
        ApiInterceptor i = interceptor("::not a url::");
        assertTrue(i.crossSite(post("jllm.example.com", "https://elsewhere.example", null)));
    }
}
