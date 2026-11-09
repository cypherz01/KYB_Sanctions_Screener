package com.cypherz01.kyb.monitoring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param recheckInterval the fallback: how often every subscription is fully re-checked
 * @param jobsPerTick how many re-checks one pass may run, which spreads them over time and keeps them
 *     inside the Companies House budget
 * @param jobRetryDelay how long a failed re-check waits before it is tried again
 * @param webhook where alerts are delivered; a blank URL means alerts are only listed through the API
 */
@ConfigurationProperties("kyb.monitoring")
public record MonitoringProperties(Duration recheckInterval, int jobsPerTick, Duration jobRetryDelay, Webhook webhook) {

    /**
     * @param url the single configured endpoint; per-subscription URLs are rejected (ADR-0006)
     * @param secret signs every delivery
     * @param maxAttempts deliveries tried before a webhook is marked failed
     * @param retryBaseDelay first backoff, doubling on each attempt
     * @param retryMaxDelay the longest backoff
     * @param timeout how long one delivery may take
     * @throws IllegalArgumentException a URL with no secret, as an unsigned webhook cannot be trusted
     */
    public record Webhook(
            String url,
            String secret,
            int maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            Duration timeout) {

        public Webhook {
            if (url != null && !url.isBlank() && (secret == null || secret.isBlank())) {
                throw new IllegalArgumentException("KYB_WEBHOOK_URL is set, so KYB_WEBHOOK_SECRET is required");
            }
        }

        /** @return whether alerts are delivered at all */
        public boolean configured() {
            return url != null && !url.isBlank();
        }
    }
}
