package com.cypherz01.kyb.api;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.cypherz01.kyb.support.PostgresTestcontainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Admin endpoints for the sanctions import (spec §7). API-key protection arrives with M5. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer.class)
class SanctionsAdminApiIntegrationTest {

    static final String IMPORT = "/api/v1/admin/sanctions/import";
    static final String STATUS = "/api/v1/admin/sanctions/status";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void sanctions(DynamicPropertyRegistry registry) {
        registry.add("kyb.sanctions.index-url", () -> wm.baseUrl() + OpenSanctionsStubs.INDEX_PATH);
        registry.add("kyb.sanctions.cron", () -> "-");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    SanctionsImportService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void reset() {
        wm.resetAll();
        jdbc.update("DELETE FROM sanctions_dataset_version");
    }

    @Test
    void statusBeforeAnyImportShowsNoDatasetAndTheAttribution() throws Exception {
        ResponseEntity<String> response = http.getForEntity(STATUS, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("active").isNull()).isTrue();
        assertThat(body.path("lastImport").isNull()).isTrue();
        assertThat(body.path("importRunning").asBoolean()).isFalse();
        assertThat(body.path("source").path("name").asText()).isEqualTo("OpenSanctions");
        assertThat(body.path("source").path("licence").asText()).isEqualTo("CC BY-NC 4.0");
    }

    @Test
    void importIsAcceptedThenCompletesInTheBackground() throws Exception {
        OpenSanctionsStubs.publish(
                wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), "v1", "sanctions/entities-v1.ftm.json");

        ResponseEntity<String> accepted = http.postForEntity(IMPORT, null, String.class);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getHeaders().getLocation()).hasPath(STATUS);
        JsonNode body = json.readTree(accepted.getBody());
        long versionId = body.path("versionId").asLong();
        assertThat(versionId).isPositive();
        assertThat(body.path("statusUrl").asText()).isEqualTo(STATUS);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode status = json.readTree(http.getForObject(STATUS, String.class));
            assertThat(status.path("active").path("id").asLong()).isEqualTo(versionId);
        });

        JsonNode status = json.readTree(http.getForObject(STATUS, String.class));
        assertThat(status.path("active").path("versionLabel").asText()).isEqualTo("v1");
        assertThat(status.path("active").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(status.path("active").path("entityCount").asLong()).isEqualTo(5);
        assertThat(status.path("active").path("nameCount").asLong()).isEqualTo(9);
        assertThat(status.path("activeEntitiesBySchema").path("Person").asLong())
                .isEqualTo(2);
        assertThat(status.path("lastImport").path("id").asLong()).isEqualTo(versionId);
        assertThat(status.path("importRunning").asBoolean()).isFalse();
    }

    @Test
    void importWhileAnotherIsRunningIsAConflictProblem() throws Exception {
        service.reserve(); // hold the slot

        ResponseEntity<String> response = http.postForEntity(IMPORT, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = json.readTree(response.getBody());
        assertThat(problem.path("status").asInt()).isEqualTo(409);
        assertThat(problem.path("detail").asText()).containsIgnoringCase("already running");

        service.recoverInterruptedImports();
    }
}
