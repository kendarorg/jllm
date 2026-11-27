package org.kendar.llm.web;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The approval dialog strips the characters that reorder or hide text; checked on the shipped script. */
class ApprovalDialogSpoofingTest {

    @Test
    void theDialogStripsZeroWidthAndBidiControlsFromWhatItShows() throws Exception {
        String js;
        try (InputStream in = getClass().getResourceAsStream("/static/components/ApprovalDialog.js")) {
            js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String u = String.valueOf((char) 92) + "u"; // the escape as written in the script
        for (String range : new String[]{u + "200B-" + u + "200F", u + "202A-" + u + "202E", u + "2066-" + u + "2069"}) {
            assertTrue(js.contains(range), "range " + range);
        }
        assertTrue(js.contains("stripSpoofing(a.value?.title)") && js.contains("stripSpoofing(a.value?.preview)"));
    }
}
