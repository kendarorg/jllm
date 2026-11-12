package org.kendar.llm.harness.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolFilterTest {

    @Test
    void absentListsShowEverything() {
        assertTrue(ToolFilter.visible(null, null, "any"));
        assertTrue(ToolFilter.visible(null, List.of(), "any"));
    }

    @Test
    void emptyAllowShowsNothingAndStarShowsAll() {
        assertFalse(ToolFilter.visible(List.of(), null, "any"));
        assertTrue(ToolFilter.visible(List.of("*"), null, "any"));
    }

    @Test
    void denyHidesAndStarHidesAll() {
        assertFalse(ToolFilter.visible(null, List.of("secret*"), "secret_key"));
        assertTrue(ToolFilter.visible(null, List.of("secret*"), "search"));
        assertFalse(ToolFilter.visible(null, List.of("*"), "search"));
    }

    @Test
    void bothListsApplyTogether() {
        assertTrue(ToolFilter.visible(List.of("search*"), List.of("search_admin"), "search_docs"));
        assertFalse(ToolFilter.visible(List.of("search*"), List.of("search_admin"), "search_admin"));
        assertFalse(ToolFilter.visible(List.of("search*"), List.of("search_admin"), "delete"));
    }

    @Test
    void restEntriesWithoutMethodMatchAnyMethod() {
        List<String> keys = ToolFilter.restKeys(List.of("GET /pets", "/pets/{id}"));
        assertEquals(List.of("GET /pets", "* /pets/{id}"), keys);
        assertTrue(ToolFilter.visible(keys, null, "DELETE /pets/{id}"));
        assertFalse(ToolFilter.visible(keys, null, "POST /pets"));
        assertTrue(ToolFilter.visible(ToolFilter.restKeys(List.of("*")), null, "POST /pets"));
        assertNull(ToolFilter.restKeys(null));
    }
}
