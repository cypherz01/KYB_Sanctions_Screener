package com.cypherz01.kyb.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MonitoringPropertiesTest {

    static MonitoringProperties.Webhook webhook(String url, String secret) {
        return new MonitoringProperties.Webhook(
                url, secret, 10, Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofSeconds(5));
    }

    /** An unsigned webhook could not be told apart from a forged one, so it must not start. */
    @Test
    void aWebhookUrlWithNoSecretIsRefused() {
        assertThatThrownBy(() -> webhook("https://example.invalid/hook", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KYB_WEBHOOK_SECRET");
    }

    @Test
    void noUrlNeedsNoSecret() {
        assertThat(webhook("", "").configured()).isFalse();
        assertThat(webhook(null, null).configured()).isFalse();
    }

    @Test
    void aUrlAndASecretAreConfigured() {
        assertThat(webhook("https://example.invalid/hook", "s3cret").configured())
                .isTrue();
    }
}
