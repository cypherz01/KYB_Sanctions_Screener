package com.cypherz01.kyb.fca;

/**
 * The Register could not be used: unreachable, non-2xx, rate-limited, circuit open, or no
 * credentials configured. {@link FcaRegisterClient} turns it into a STALE or UNAVAILABLE
 * assessment and never lets it escape.
 */
public class FcaUnavailableException extends RuntimeException {

    public FcaUnavailableException(String message) {
        super(message);
    }

    public FcaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
