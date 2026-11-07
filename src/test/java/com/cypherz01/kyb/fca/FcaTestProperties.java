package com.cypherz01.kyb.fca;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Shipped defaults (application.yml) with a test base URL, credentials and a small breaker window. */
final class FcaTestProperties {

    private FcaTestProperties() {}

    static FcaProperties with(String baseUrl, String email, String key) {
        return new FcaProperties(
                URI.create(baseUrl),
                email,
                key,
                Duration.ofDays(7),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                new FcaProperties.RateLimit(45, Duration.ofSeconds(10), Duration.ofSeconds(2)),
                // small window so a test can open the breaker in a few calls
                new FcaProperties.CircuitBreaker(50f, 4, 3, Duration.ofSeconds(60), 1),
                3,
                List.of("Authorised", "EEA Authorised", "Registered"),
                List.of("Unauthorised"),
                List.of("clone", "unauthorised", "not authorised"),
                0.05);
    }
}
