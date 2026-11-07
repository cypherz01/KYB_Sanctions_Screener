package com.cypherz01.kyb.companieshouse;

import java.time.Duration;
import java.util.Optional;

/** A failure worth retrying (429, 5xx, I/O), optionally with the server's suggested wait. */
class RetryableUpstreamException extends RuntimeException {

    private final transient Duration retryAfter;

    RetryableUpstreamException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
