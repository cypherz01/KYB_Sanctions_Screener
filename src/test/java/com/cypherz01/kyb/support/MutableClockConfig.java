package com.cypherz01.kyb.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfig {

    public static final Instant T0 = Instant.parse("2026-11-07T12:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
