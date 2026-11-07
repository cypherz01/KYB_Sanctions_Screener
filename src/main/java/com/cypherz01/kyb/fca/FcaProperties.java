package com.cypherz01.kyb.fca;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FCA Register client settings (spec §5.2). {@link #toString()} masks the key.
 *
 * @param maxDetailsLookups firm-details calls per check (one per strongest authorised name hit)
 * @param authorisedStatuses Register statuses that count as holding an authorisation
 * @param warningStatuses statuses of unauthorised-firm entries
 * @param warningNameMarkers lower-case substrings marking clone or unauthorised entries by name
 * @param postcodeBoost added to name similarity when FCA and Companies House postcodes agree
 */
@ConfigurationProperties("kyb.fca")
public record FcaProperties(
        URI baseUrl,
        String authEmail,
        String authKey,
        Duration cacheTtl,
        Duration connectTimeout,
        Duration readTimeout,
        RateLimit rateLimit,
        CircuitBreaker circuitBreaker,
        int maxDetailsLookups,
        List<String> authorisedStatuses,
        List<String> warningStatuses,
        List<String> warningNameMarkers,
        double postcodeBoost) {

    public record RateLimit(int limit, Duration period, Duration maxWait) {}

    public record CircuitBreaker(
            float failureRateThreshold,
            int slidingWindowSize,
            int minimumNumberOfCalls,
            Duration waitDurationInOpenState,
            int permittedCallsInHalfOpenState) {}

    /** Both credentials present. Without them no call is attempted. */
    public boolean hasCredentials() {
        return authEmail != null && !authEmail.isBlank() && authKey != null && !authKey.isBlank();
    }

    @Override
    public String toString() {
        return "FcaProperties[baseUrl=" + baseUrl + ", authEmail="
                + (authEmail == null || authEmail.isBlank() ? "<unset>" : "****")
                + ", authKey=" + (authKey == null || authKey.isBlank() ? "<unset>" : "****")
                + ", cacheTtl=" + cacheTtl + ", rateLimit=" + rateLimit + ", circuitBreaker=" + circuitBreaker
                + ", maxDetailsLookups=" + maxDetailsLookups + "]";
    }
}
