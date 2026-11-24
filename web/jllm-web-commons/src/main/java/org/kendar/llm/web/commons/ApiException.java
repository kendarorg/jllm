package org.kendar.llm.web.commons;

/** A failed call: the HTTP status and the message the client sees as {@code {"error": message}}. */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
