package org.kendar.llm.harness.settings;

/** @param maxBytes cap of the total size of a chat's storage; null or -1 = no limit (default) */
public record StorageSettings(Long maxBytes) {
}
