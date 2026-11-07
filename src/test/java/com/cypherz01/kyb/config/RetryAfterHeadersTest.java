package com.cypherz01.kyb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class RetryAfterHeadersTest {

    static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void retryAfterDeltaSeconds() {
        assertThat(RetryAfterHeaders.parse(headers("Retry-After", "3"), clock)).contains(Duration.ofSeconds(3));
    }

    @Test
    void retryAfterHttpDate() {
        HttpHeaders h = headers("Retry-After", "Sat, 07 Nov 2026 12:00:05 GMT");

        assertThat(RetryAfterHeaders.parse(h, clock)).contains(Duration.ofSeconds(5));
    }

    @Test
    void retryAfterInThePastMeansRetryNow() {
        HttpHeaders h = headers("Retry-After", "Sat, 07 Nov 2026 11:59:00 GMT");

        assertThat(RetryAfterHeaders.parse(h, clock)).contains(Duration.ZERO);
    }

    @Test
    void rateLimitResetEpochSeconds() {
        long reset = NOW.plusSeconds(30).getEpochSecond();

        assertThat(RetryAfterHeaders.parse(headers("X-Ratelimit-Reset", Long.toString(reset)), clock))
                .contains(Duration.ofSeconds(30));
    }

    @Test
    void retryAfterTakesPrecedenceOverRateLimitReset() {
        HttpHeaders h = headers("Retry-After", "2");
        h.add("X-Ratelimit-Reset", Long.toString(NOW.plusSeconds(90).getEpochSecond()));

        assertThat(RetryAfterHeaders.parse(h, clock)).contains(Duration.ofSeconds(2));
    }

    @Test
    void headerNamesAreCaseInsensitive() {
        assertThat(RetryAfterHeaders.parse(headers("retry-after", "4"), clock)).contains(Duration.ofSeconds(4));
    }

    @Test
    void unparseableOrMissingHeadersGiveNoHint() {
        assertThat(RetryAfterHeaders.parse(headers("Retry-After", "soon"), clock))
                .isEmpty();
        assertThat(RetryAfterHeaders.parse(headers("X-Ratelimit-Reset", "tomorrow"), clock))
                .isEmpty();
        assertThat(RetryAfterHeaders.parse(new HttpHeaders(), clock)).isEmpty();
    }

    @Test
    void negativeDeltaIsIgnored() {
        assertThat(RetryAfterHeaders.parse(headers("Retry-After", "-5"), clock)).isEmpty();
    }

    private static HttpHeaders headers(String name, String value) {
        HttpHeaders h = new HttpHeaders();
        h.add(name, value);
        return h;
    }
}
