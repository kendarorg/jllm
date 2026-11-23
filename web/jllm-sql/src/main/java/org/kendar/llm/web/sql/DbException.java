package org.kendar.llm.web.sql;

/** A database failure, unchecked. */
public class DbException extends RuntimeException {

    public DbException(String message, Throwable cause) {
        super(message, cause);
    }
}
