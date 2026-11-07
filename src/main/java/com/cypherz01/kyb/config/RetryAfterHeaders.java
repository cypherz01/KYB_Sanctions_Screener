package com.cypherz01.kyb.config;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

/**
 * Extracts a server-suggested wait from a 429/503 response. The Companies House docs don't promise
 * any such header, so every one is optional and anything unparseable is ignored:
 *
 * <ol>
 *   <li>{@code Retry-After}: delta-seconds or an HTTP-date (RFC 9110)
 *   <li>{@code X-Ratelimit-Reset}: epoch seconds at which the window resets
 * </ol>
 *
 * Times in the past give {@link Duration#ZERO}.
 */
public final class RetryAfterHeaders {

    private RetryAfterHeaders() {}

    public static Optional<Duration> parse(HttpHeaders headers, Clock clock) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
