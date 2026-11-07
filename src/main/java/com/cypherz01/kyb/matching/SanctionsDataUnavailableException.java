package com.cypherz01.kyb.matching;

/**
 * No active sanctions dataset, so screening would silently pass everyone. Maps to HTTP 503: an
 * empty result here must never be read as "no match".
 */
public class SanctionsDataUnavailableException extends RuntimeException {

    public SanctionsDataUnavailableException(String message) {
        super(message);
    }
}
