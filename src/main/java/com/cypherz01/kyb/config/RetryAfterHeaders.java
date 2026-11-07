package com.cypherz01.kyb.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;
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

    static final String RATE_LIMIT_RESET = "X-Ratelimit-Reset";
    private static final Pattern DELTA_SECONDS = Pattern.compile("\\d{1,9}");

    private RetryAfterHeaders() {}

    public static Optional<Duration> parse(HttpHeaders headers, Clock clock) {
        Instant now = clock.instant();
        Optional<Duration> retryAfter = parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER), now);
        if (retryAfter.isPresent()) {
            return retryAfter;
        }
        return parseEpochReset(headers.getFirst(RATE_LIMIT_RESET), now);
    }

    private static Optional<Duration> parseRetryAfter(String value, Instant now) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim();
        if (DELTA_SECONDS.matcher(v).matches()) {
            return Optional.of(Duration.ofSeconds(Long.parseLong(v)));
        }
        try {
            Instant at =
                    ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Optional.of(untilOrZero(now, at));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> parseEpochReset(String value, Instant now) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(untilOrZero(now, Instant.ofEpochSecond(Long.parseLong(value.trim()))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Duration untilOrZero(Instant now, Instant at) {
        Duration d = Duration.between(now, at);
        return d.isNegative() ? Duration.ZERO : d;
    }
}
