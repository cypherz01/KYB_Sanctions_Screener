package com.cypherz01.kyb.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.support.ApiIntegrationTest;
import com.cypherz01.kyb.support.ApiKeyTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Spec §7: an {@code X-Api-Key} is required on every non-health endpoint under {@code /api}.
 * Health, metrics and the API docs stay open (local-only deployment; documented in the README).
 */
@ApiIntegrationTest
@AutoConfigureObservability
class ApiSecurityIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/sanctions/status", "/api/v1/checks/3f1c2b7e-0000-4000-8000-000000000000"})
    void aMissingKeyIsUnauthorised(String path) throws Exception {
        assertUnauthorised(http.exchange(path, HttpMethod.GET, withKey(""), String.class));
    }

    @Test
    void aWrongKeyIsUnauthorised() throws Exception {
        assertUnauthorised(
                http.exchange("/api/v1/admin/sanctions/status", HttpMethod.GET, withKey("wrong-key"), String.class));
    }

    @Test
    void aPostWithoutAKeyIsRejectedBeforeItRuns() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ApiKeyTestConfig.HEADER, "");
        headers.setContentType(MediaType.APPLICATION_JSON);

        assertUnauthorised(http.exchange(
                "/api/v1/screen/name",
                HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"Ivan Petrov\"}", headers),
                String.class));
    }

    @Test
    void theRightKeyIsAccepted() {
        ResponseEntity<String> r = http.exchange(
                "/api/v1/admin/sanctions/status", HttpMethod.GET, withKey(ApiKeyTestConfig.KEY), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/prometheus", "/v3/api-docs", "/swagger-ui/index.html"})
    void operationalEndpointsAndDocsNeedNoKey(String path) {
        assertThat(http.exchange(path, HttpMethod.GET, withKey(""), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void theApiDocsDescribeTheEndpointsAndTheKey() throws Exception {
        JsonNode docs = json.readTree(http.exchange("/v3/api-docs", HttpMethod.GET, withKey(""), String.class)
                .getBody());

        assertThat(docs.path("paths").has("/api/v1/checks")).isTrue();
        assertThat(docs.path("paths").has("/api/v1/checks/{checkId}")).isTrue();
        assertThat(docs.path("paths").has("/api/v1/screen/name")).isTrue();
        assertThat(docs.path("paths").has("/api/v1/admin/sanctions/import")).isTrue();
        assertThat(docs.toString()).contains("X-Api-Key");
    }

    private static HttpEntity<Void> withKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ApiKeyTestConfig.HEADER, key);
        return new HttpEntity<>(headers);
    }

    private void assertUnauthorised(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json.readTree(r.getBody()).path("detail").asText()).contains("X-Api-Key");
    }
}
