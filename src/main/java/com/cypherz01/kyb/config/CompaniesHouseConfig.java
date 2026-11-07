package com.cypherz01.kyb.config;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.companieshouse.CompaniesHouseApi;
import com.cypherz01.kyb.companieshouse.CompaniesHouseClient;
import com.cypherz01.kyb.companieshouse.CompaniesHouseMapper;
import com.cypherz01.kyb.companieshouse.CompaniesHouseProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CompaniesHouseProperties.class)
class CompaniesHouseConfig {

    @Bean
    CompaniesHouseApi companiesHouseApi(
            RestClient.Builder builder, CompaniesHouseProperties properties, Clock clock, MeterRegistry meters) {
        return new CompaniesHouseApi(
                CompaniesHouseApi.buildRestClient(builder, properties),
                properties,
                clock,
                meters,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    CompaniesHouseMapper companiesHouseMapper(ObjectMapper objectMapper) {
        return new CompaniesHouseMapper(objectMapper);
    }

    @Bean
    CompaniesHouseClient companiesHouseClient(
            CompaniesHouseApi api,
            SourceCache cache,
            CompaniesHouseMapper mapper,
            CompaniesHouseProperties properties,
            Clock clock,
            MeterRegistry meters) {
        return new CompaniesHouseClient(api, cache, mapper, properties, clock, meters);
    }
}
