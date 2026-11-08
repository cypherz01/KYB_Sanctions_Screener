package com.cypherz01.kyb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;

class CoreConfigTest {

    /**
     * Postgres stores microseconds. A finer clock means a timestamp reads back different from the
     * one written, and a replay rebuilt from stored data no longer equals the original result.
     */
    @Test
    void theClockIsNoFinerThanWhatPostgresStores() {
        Clock clock = new CoreConfig().clock();

        for (int i = 0; i < 1000; i++) {
            assertThat(clock.instant().getNano() % 1000).isZero();
        }
    }
}
