package org.kendar.llm.harness.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FallbackModelClientTest {

    private static ModelClient failing(String msg) {
        ModelClient c = mock(ModelClient.class);
        when(c.simpleComplete(any(), any(), anyInt())).thenThrow(new ModelException(msg));
        return c;
    }

    private static ModelClient ok(String text) {
        ModelClient c = mock(ModelClient.class);
        when(c.simpleComplete(any(), any(), anyInt())).thenReturn(text);
        return c;
    }

    @Test
    void movesToTheNextProviderOnFailure() {
        List<String> switched = new ArrayList<>();
        FallbackModelClient f = new FallbackModelClient(List.of(
                new FallbackModelClient.Candidate("a", () -> failing("HTTP 503")),
                new FallbackModelClient.Candidate("b", () -> ok("from-b"))))
                .onFallback(switched::add);
        assertEquals("from-b", f.simpleComplete("s", "u", 10));
        assertEquals(List.of("b"), switched);
    }

    @Test
    void contextOverflowIsNotRetriedElsewhere() {
        ModelClient b = ok("x");
        FallbackModelClient f = new FallbackModelClient(List.of(
                new FallbackModelClient.Candidate("a", () -> failing("prompt is too long")),
                new FallbackModelClient.Candidate("b", () -> b)));
        assertThrows(ModelException.class, () -> f.simpleComplete("s", "u", 10));
        verifyNoInteractions(b);
    }

    @Test
    void failsWithTheLastErrorWhenAllFail() {
        FallbackModelClient f = new FallbackModelClient(List.of(
                new FallbackModelClient.Candidate("a", () -> failing("a down")),
                new FallbackModelClient.Candidate("b", () -> failing("b down"))));
        ModelException e = assertThrows(ModelException.class, () -> f.simpleComplete("s", "u", 10));
        assertEquals("b down", e.getMessage());
    }

    @Test
    void catalogChainsProvidersByOrder() {
        ProviderSettings a = new ProviderSettings("a", "llamacpp", "http://localhost:1/v1", null, "m", null, null,
                null, null, null, null, null, null, 1);
        ProviderSettings b = new ProviderSettings("b", "llamacpp", "http://localhost:1/v1", null, "m", null, null,
                null, null, null, null, null, null, 2);
        ProviderSettings c = new ProviderSettings("c", "llamacpp", "http://localhost:1/v1", null, "m", null, null,
                null, null, null, null, null, null, null);
        ProviderCatalog cat = new ProviderCatalog(List.of(a, b, c), new com.fasterxml.jackson.databind.ObjectMapper(), 100);
        assertInstanceOf(FallbackModelClient.class, cat.client("a"));
        assertInstanceOf(FallbackModelClient.class, cat.client("c"));
        assertFalse(cat.client("b") instanceof FallbackModelClient);
    }
}
