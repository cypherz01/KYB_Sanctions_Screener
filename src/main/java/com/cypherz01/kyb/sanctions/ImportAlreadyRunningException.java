package com.cypherz01.kyb.sanctions;

/** Another import holds the single LOADING slot. Maps to HTTP 409 on the admin endpoint. */
public class ImportAlreadyRunningException extends RuntimeException {

    public ImportAlreadyRunningException(String message) {
        super(message);
    }

    public ImportAlreadyRunningException(String message, Throwable cause) {
        super(message, cause);
    }
}
