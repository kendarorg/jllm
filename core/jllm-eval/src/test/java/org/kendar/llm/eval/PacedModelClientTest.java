package org.kendar.llm.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PacedModelClientTest {

    private static ModelClient failing(AtomicInteger calls, int failures, String message) {
        return new ModelClient() {
            @Override
            public org.kendar.llm.harness.llm.ModelResponse complete(org.kendar.llm.harness.llm.ModelRequest r) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String simpleComplete(String system, String user, int maxTokens) {
                if (calls.incrementAndGet() <= failures) throw new ModelException(message);
                return "ok";
            }
        };
    }

    private static final String LIMITED = "llamacpp API error 429: {\"status\":429,\"title\":\"Too Many Requests\"}";

    @Test
    void a429IsRepeatedAfterThePauseAndThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        var c = new PacedModelClient(failing(calls, 2, LIMITED), new RequestPacer(6000), 3, 5, 1);
        assertEquals("ok", c.simpleComplete("s", "u", 10));
        assertEquals(3, calls.get());
    }

    @Test
    void givesUpAfterTheConfiguredRepeats() {
        AtomicInteger calls = new AtomicInteger();
        var c = new PacedModelClient(failing(calls, 99, LIMITED), new RequestPacer(6000), 3, 5, 1);
        assertThrows(ModelException.class, () -> c.simpleComplete("s", "u", 10));
        assertEquals(4, calls.get(), "the first call plus three repeats");
    }

    @Test
    void otherErrorsAreNotRepeated() {
        AtomicInteger calls = new AtomicInteger();
        var c = new PacedModelClient(failing(calls, 99, "llamacpp API error 500: boom"), new RequestPacer(6000), 3, 5, 1);
        assertThrows(ModelException.class, () -> c.simpleComplete("s", "u", 10));
        assertEquals(1, calls.get());
    }

    @Test
    void theProvidersRetryAfterIsRead() {
        var e = new ModelException(LIMITED + " [retry-after=30, x-ratelimit-reset=12s]");
        assertEquals(30_000L, PacedModelClient.retryAfterMs(e));
        assertEquals(120_000L, PacedModelClient.retryAfterMs(new ModelException("429 [retry-after=900]")));
        assertEquals(0L, PacedModelClient.retryAfterMs(new ModelException(LIMITED)));
        assertTrue(PacedModelClient.isRateLimited(e));
    }

    @Test
    void aPauseDelaysEveryLaterSlotOfTheHost() {
        RequestPacer p = new RequestPacer(6000);
        p.pauseMs(10_000);
        assertTrue(p.reserveMs() > 9_000);
    }
}
