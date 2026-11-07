package com.cypherz01.kyb.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Makes the test's TestRestTemplate send the test API key, unless a request already sets the
 * header (so a test can still send a wrong or empty key on purpose).
 */
@TestConfiguration(proxyBeanMethods = false)
public class ApiKeyTestConfig {

    public static final String KEY = "test-api-key-3b9f";
    public static final String HEADER = "X-Api-Key";

    @Bean
    RestTemplateCustomizer apiKeyHeader() {
        return restTemplate -> restTemplate.getInterceptors().add((request, body, execution) -> {
            if (!request.getHeaders().containsKey(HEADER)) {
                request.getHeaders().set(HEADER, KEY);
            }
            return execution.execute(request, body);
        });
    }
}
