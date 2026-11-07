package com.cypherz01.kyb.companieshouse;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Companies House client settings. Defaults live in application.yml (no hard-coded URLs or limits
 * in code, spec §14). {@link #toString()} masks the API key so it can never leak through logging
 * or error messages.
 */
@ConfigurationProperties("kyb.companies-house")
public record CompaniesHouseProperties(
        URI baseUrl,
        String apiKey,
        Duration cacheTtl,
        int itemsPerPage,
        int maxPages,
        Duration connectTimeout,
        Duration readTimeout,
        RateLimit rateLimit,
        Retry retry) {

    /** Client-side limiter, set below the upstream 600 requests / 5 minutes. */
    public record RateLimit(int limit, Duration period, Duration maxWait) {}

    /** Retries on 429/5xx/IO errors. {@code maxRetries} excludes the initial attempt. */
    public record Retry(int maxRetries, Duration baseDelay, Duration maxDelay) {}

    @Override
    public String toString() {
        return "CompaniesHouseProperties[baseUrl=" + baseUrl + ", apiKey="
                + (apiKey == null || apiKey.isBlank() ? "<unset>" : "****")
                + ", cacheTtl=" + cacheTtl + ", itemsPerPage=" + itemsPerPage + ", maxPages=" + maxPages
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
                + ", rateLimit=" + rateLimit + ", retry=" + retry + "]";
    }
}
