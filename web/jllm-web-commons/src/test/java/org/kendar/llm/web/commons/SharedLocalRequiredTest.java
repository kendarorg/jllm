package org.kendar.llm.web.commons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharedLocalRequiredTest {

    private static JllmProperties props(Boolean allowMissingBwrap) {
        return new JllmProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, new JllmProperties.Web(null, null, null, null, allowMissingBwrap, null, null));
    }

    @Test
    void aMissingBwrapStopsTheStartByDefaultWhenSharedLocalIsUsed() {
        assertTrue(HarnessFactory.sharedLocalRequired(props(null), false, true));
        assertTrue(HarnessFactory.sharedLocalRequired(props(false), true, false));
        assertTrue(HarnessFactory.sharedLocalRequired(JllmProperties.defaults(), true, true));
        assertFalse(HarnessFactory.sharedLocalRequired(props(false), false, false), "not used: nothing to fail");
    }

    @Test
    void allowMissingBwrapRestoresTheSoftBehaviour() {
        assertFalse(HarnessFactory.sharedLocalRequired(props(true), true, true));
        assertFalse(HarnessFactory.sharedLocalRequired(props(true), false, true));
    }
}
