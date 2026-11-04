package org.kendar.llm.harness.llm;

/** MAIN does the work; FAST is a small cheap model for classification, summaries and tool-group selection. */
public enum ModelRole {
    MAIN, FAST
}
