package org.kendar.llm.web.commons;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A download: written to the response when the framework asks for it, chunk by chunk.
 *
 * @param contentType the media type
 * @param length      bytes, or null when not known in advance
 * @param filename    the attachment's name, or null for inline
 * @param writer      writes the bytes
 */
public record ByteStream(String contentType, Long length, String filename, Writer writer) {

    @FunctionalInterface
    public interface Writer {
        void writeTo(OutputStream out) throws IOException;
    }
}
