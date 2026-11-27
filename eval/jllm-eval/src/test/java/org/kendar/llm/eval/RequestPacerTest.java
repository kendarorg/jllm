package org.kendar.llm.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RequestPacerTest {

    @Test
    void fortyPerMinuteSpacesStartsOneAndAHalfSecondsApart() {
        RequestPacer pacer = new RequestPacer(40);
        assertEquals(1_500L, pacer.intervalMs());
        assertEquals(0L, pacer.reserveMs());
        long second = pacer.reserveMs();
        long third = pacer.reserveMs();
        assertTrue(second > 1_000L && second <= 1_500L, "second wait " + second);
        assertTrue(third > second + 1_000L && third <= 3_000L, "third wait " + third);
    }

    @Test
    void endpointsOnTheSameHostShareOnePacer() {
        RequestPacer a = RequestPacer.forHost("https://integrate.api.nvidia.com/v1/", 40);
        RequestPacer b = RequestPacer.forHost("HTTPS://integrate.api.nvidia.com/v1", 40);
        assertSame(a, b);
    }
}
