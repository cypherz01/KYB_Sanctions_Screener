package com.cypherz01.kyb.config;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.cache.TwoTierSourceCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class CoreConfig {

    /** Injected everywhere time matters (TTLs, audit timestamps) so tests can control it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SourceCache sourceCache(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${kyb.cache.max-local-entries}") long maxLocalEntries) {
        return new TwoTierSourceCache(jdbc, objectMapper, maxLocalEntries);
    }
}
