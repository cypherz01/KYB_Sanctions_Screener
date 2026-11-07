package com.cypherz01.kyb.companieshouse;

/**
 * Companies House could not be reached or kept refusing (5xx, 429 after retries, timeouts,
 * client-side rate limit exhausted, rejected credentials). Maps to HTTP 503 in M5, but only when no
 * cached copy, even a stale one, is available.
 */
public class CompaniesHouseUnavailableException extends RuntimeException {

    public CompaniesHouseUnavailableException(String message) {
        super(message);
    }

    public CompaniesHouseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
