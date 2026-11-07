package com.cypherz01.kyb.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.support.PostgresTestcontainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** With no KYB_API_KEY configured the API fails closed: nothing is served, whatever is sent. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kyb.security.api-key=", "kyb.sanctions.cron=-"})
@Import(PostgresTestcontainer.class)
class ApiKeyNotConfiguredIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Test
    void everyApiCallIsRefusedAndTheReasonIsGiven() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", "anything");

        ResponseEntity<String> r = http.exchange(
                "/api/v1/admin/sanctions/status", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json.readTree(r.getBody()).path("detail").asText()).containsIgnoringCase("not configured");
    }

    @Test
    void healthStillWorks() {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
