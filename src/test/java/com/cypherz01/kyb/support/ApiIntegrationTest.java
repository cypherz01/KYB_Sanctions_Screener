package com.cypherz01.kyb.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * An HTTP-level integration test: real server on a random port, Postgres, the API key configured
 * and sent by the injected TestRestTemplate, and the nightly sanctions import disabled.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kyb.security.api-key=" + ApiKeyTestConfig.KEY, "kyb.sanctions.cron=-"})
@Import({PostgresTestcontainer.class, ApiKeyTestConfig.class})
public @interface ApiIntegrationTest {}
