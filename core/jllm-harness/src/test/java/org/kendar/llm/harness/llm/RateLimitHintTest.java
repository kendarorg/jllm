package org.kendar.llm.harness.llm;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitHintTest {

    private static HttpResponse<String> responseWith(Map<String, List<String>> headers) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.headers()).thenReturn(HttpHeaders.of(headers, (k, v) -> true));
        return r;
    }

    @Test
    void retryAfterAndRateLimitHeadersAreReported() {
        String hint = StreamingCall.rateLimitHint(responseWith(Map.of(
                "Retry-After", List.of("30"), "X-RateLimit-Reset-Requests", List.of("12s"), "Content-Type", List.of("x"))));
        assertTrue(hint.startsWith(" [") && hint.endsWith("]"), hint);
        assertTrue(hint.contains("retry-after=30"), hint);
        assertTrue(hint.contains("x-ratelimit-reset-requests=12s"), hint);
        assertFalse(hint.contains("content-type"), hint);
    }

    @Test
    void noSuchHeadersMeansNoSuffix() {
        assertEquals("", StreamingCall.rateLimitHint(responseWith(Map.of("Content-Type", List.of("x")))));
    }
}
