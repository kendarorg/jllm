package org.kendar.llm.js;

/** Result of one run, rendered as the {@code run_js} tool output. */
public record JsResult(boolean ok, String value, String console, String error, boolean truncated, String fileRoot) {
}
