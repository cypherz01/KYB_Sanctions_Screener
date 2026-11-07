package com.cypherz01.kyb.config;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.fca.FcaMapper;
import com.cypherz01.kyb.fca.FcaProperties;
import com.cypherz01.kyb.fca.FcaRegisterApi;
import com.cypherz01.kyb.fca.FcaRegisterClient;
import com.cypherz01.kyb.matching.NameMatcher;
import com.cypherz01.kyb.matching.NameNormaliser;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FcaProperties.class)
class FcaConfig {

    @Bean
    FcaRegisterApi fcaRegisterApi(RestClient.Builder builder, FcaProperties properties, MeterRegistry meters) {
        return new FcaRegisterApi(FcaRegisterApi.buildRestClient(builder, properties), properties, meters);
    }

    @Bean
    FcaRegisterClient fcaRegisterClient(
            FcaRegisterApi api,
            SourceCache cache,
            NameNormaliser normaliser,
            NameMatcher matcher,
            FcaProperties properties,
            Clock clock,
            MeterRegistry meters) {
        return new FcaRegisterClient(api, cache, new FcaMapper(), normaliser, matcher, properties, clock, meters);
    }
}
