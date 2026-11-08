package com.cypherz01.kyb.config;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.cache.TwoTierSourceCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class CoreConfig {

    /**
     * Injected everywhere time matters (TTLs, audit timestamps) so tests can control it. It ticks in
     * microseconds, the finest Postgres stores, so a timestamp reads back equal to the one written:
     * a replay rebuilt from stored data could not otherwise match the original result.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.of(1, ChronoUnit.MICROS));
    }

    @Bean
    SourceCache sourceCache(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${kyb.cache.max-local-entries}") long maxLocalEntries) {
        return new TwoTierSourceCache(jdbc, objectMapper, maxLocalEntries);
    }
}
