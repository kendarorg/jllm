package org.kendar.llm.harness.settings;

/**
 * Where OAuth tokens are kept.
 *
 * @param type    {@code auto} (default): the OS secret store for a single user when it works, else the file;
 *                {@code file}; {@code native} (single user only)
 * @param dir     directory of the file store; default {@code ~/.jllm} (single user), the embedder's choice with
 *                users (jllm-web: {@code <webauth>/tokens})
 * @param encrypt encrypt the files with {@code JLLM_TOKEN_KEY}; default false for a single user, with users true
 *                when the key is set (an embedder with per-user sources requires it, jllm-web does)
 */
public record TokenStoreSettings(String type, String dir, Boolean encrypt) {
}
