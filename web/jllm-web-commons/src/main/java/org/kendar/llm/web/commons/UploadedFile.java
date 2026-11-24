package org.kendar.llm.web.commons;

import java.io.InputStream;

/**
 * One uploaded file.
 *
 * @param name    the client's file name (may hold a path)
 * @param size    bytes
 * @param content the bytes; the handler closes it
 */
public record UploadedFile(String name, long size, InputStream content) {
}
