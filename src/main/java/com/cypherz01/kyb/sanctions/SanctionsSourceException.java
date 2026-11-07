package com.cypherz01.kyb.sanctions;

/** The OpenSanctions index or file could not be fetched or understood. */
public class SanctionsSourceException extends RuntimeException {

    public SanctionsSourceException(String message) {
        super(message);
    }

    public SanctionsSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
