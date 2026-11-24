package org.kendar.llm.web.commons;

import java.io.IOException;

/**
 * Where a server-sent event stream is written: the web framework supplies the implementation. A failed write means
 * the client is gone.
 */
public interface EventSink {

    /** One event; {@code id} may be null. */
    void send(String id, String event, String data) throws IOException;

    /** A comment line (a heartbeat). */
    void comment(String text) throws IOException;

    /** Ends the stream. */
    void complete();

    /** Ends the stream because a write failed. */
    void completeWithError(Throwable error);

    /** Runs when the stream ends, whatever the reason; at once if it already has. */
    void onClose(Runnable action);

    /** Runs when the stream times out, before it is completed. */
    void onTimeout(Runnable action);
}
