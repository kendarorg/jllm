package org.kendar.llm.harness.fs;

/** A file-explorer failure with the HTTP status it maps to (400, 404, 409, 413, 415, 502, 503). */
public class FsException extends RuntimeException {
    private final int status;

    public FsException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
