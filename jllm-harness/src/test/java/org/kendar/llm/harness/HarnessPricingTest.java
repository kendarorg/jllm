package org.kendar.llm.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HarnessPricingTest {

    @Test
    void defaultsAreFree() {
        assertEquals(0.0, new HarnessPricing(0, 0, 0, 0).estimate(1_000_000, 1_000_000, 1_000_000, 1_000_000));
    }

    @Test
    void inputOutputPriced() {
        HarnessPricing p = new HarnessPricing(3, 15, 0, 0);
        assertEquals(3 + 7.5, p.estimate(1_000_000, 500_000), 1e-9);
    }

    @Test
    void cacheTokensPricedSeparatelyAndUnpricedByDefault() {
        HarnessPricing p = new HarnessPricing(3, 15, 0.3, 3.75);
        assertEquals(0.3 + 3.75, p.estimate(0, 0, 1_000_000, 1_000_000), 1e-9);
        HarnessPricing unpriced = new HarnessPricing(3, 15, 0, 0);
        assertEquals(0.0, unpriced.estimate(0, 0, 1_000_000, 1_000_000), 1e-9);
    }
}
