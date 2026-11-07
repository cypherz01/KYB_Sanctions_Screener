package com.cypherz01.kyb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    static final Duration BASE = Duration.ofMillis(100);
    static final Duration MAX = Duration.ofSeconds(2);

    @Test
    void exponentialWithMinimumJitterIsHalfTheExponentialDelay() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, () -> 0.0);

        assertThat(policy.delayFor(1, Optional.empty())).isEqualTo(Duration.ofMillis(50));
        assertThat(policy.delayFor(2, Optional.empty())).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayFor(3, Optional.empty())).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void exponentialWithMaximumJitterIsTheFullExponentialDelay() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, () -> 1.0);

        assertThat(policy.delayFor(1, Optional.empty())).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayFor(2, Optional.empty())).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.delayFor(3, Optional.empty())).isEqualTo(Duration.ofMillis(400));
    }

    @Test
    void jitterStaysWithinBounds() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, Math::random);

        for (int i = 0; i < 1_000; i++) {
            assertThat(policy.delayFor(3, Optional.empty())).isBetween(Duration.ofMillis(200), Duration.ofMillis(400));
        }
    }

    @Test
    void exponentialDelayIsCappedAndNeverOverflows() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, () -> 1.0);

        assertThat(policy.delayFor(10, Optional.empty())).isEqualTo(MAX);
        assertThat(policy.delayFor(100, Optional.empty())).isEqualTo(MAX);
    }

    @Test
    void upstreamHintIsHonouredInsteadOfExponentialDelay() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, () -> 1.0);

        assertThat(policy.delayFor(1, Optional.of(Duration.ofMillis(1_500)))).isEqualTo(Duration.ofMillis(1_500));
        assertThat(policy.delayFor(1, Optional.of(Duration.ZERO))).isEqualTo(Duration.ZERO);
    }

    @Test
    void upstreamHintIsCappedSoARequestNeverWaitsOutAFiveMinuteWindow() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, () -> 1.0);

        assertThat(policy.delayFor(1, Optional.of(Duration.ofMinutes(4)))).isEqualTo(MAX);
    }
}
