package com.cypherz01.kyb.api;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.sanctions.ImportOutcome;
import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.support.ApiIntegrationTest;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@code POST /api/v1/screen/name} (spec §7). The API key is sent by @ApiIntegrationTest. */
@ApiIntegrationTest
class ScreeningApiIntegrationTest {

    static final String SCREEN = "/api/v1/screen/name";

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
    SanctionsImportService imports;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void loadDataset() {
        wm.resetAll();
        jdbc.update("DELETE FROM sanctions_dataset_version");
        OpenSanctionsStubs.publish(
                wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), "api-v1", "sanctions/entities-screening.ftm.json");
        assertThat(imports.importLatest().outcome()).isEqualTo(ImportOutcome.COMPLETED);
    }

    @Test
    void screensANameAndExplainsTheMatch() throws Exception {
        ResponseEntity<String> response = http.postForEntity(
                SCREEN,
                Map.of("name", "Ivan PETROV", "type", "person", "birthDate", "1971-03", "country", "ru"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("query").asText()).isEqualTo("Ivan PETROV");
        assertThat(body.path("subjectType").asText()).isEqualTo("PERSON");
        assertThat(body.path("normalisedQuery").asText()).isEqualTo("ivan petrov");
        assertThat(body.path("datasetVersion").path("version").asText()).isEqualTo("api-v1");
        assertThat(body.path("datasetVersion").path("id").asLong()).isPositive();
        assertThat(body.path("candidatesConsidered").asInt()).isPositive();
        assertThat(body.path("source").path("licence").asText()).isEqualTo("CC BY-NC 4.0");

        JsonNode match = body.path("matches").get(0);
        assertThat(match.path("sanctionsEntityId").asText()).isEqualTo("NK-ivan");
        assertThat(match.path("band").asText()).isEqualTo("STRONG");
        assertThat(match.path("similarity").asDouble()).isBetween(0.9, 1.0);
        assertThat(match.path("matchedName").asText()).isEqualTo("Ivan Petrov");
        assertThat(match.path("lists")).extracting(JsonNode::asText).contains("UK FCDO");
        assertThat(match.path("evidence").isArray()).isTrue();
        assertThat(match.path("evidence").size()).isPositive();
    }

    @Test
    void typeIsOptional() throws Exception {
        ResponseEntity<String> response = http.postForEntity(SCREEN, Map.of("name", "Acme Trading"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("subjectType").asText()).isEqualTo("ANY");
        assertThat(body.path("matches").get(0).path("sanctionsEntityId").asText())
                .isEqualTo("NK-acme");
    }

    @Test
    void anUnmatchedNameReturnsAnEmptyList() throws Exception {
        JsonNode body = json.readTree(http.postForEntity(SCREEN, Map.of("name", "Zebedee Quux"), String.class)
                .getBody());

        assertThat(body.path("matches").isArray()).isTrue();
        assertThat(body.path("matches").size()).isZero();
    }

    @Test
    void aBlankNameIsABadRequestProblem() throws Exception {
        assertProblem(http.postForEntity(SCREEN, Map.of("name", "  "), String.class), 400, "name");
    }

    @Test
    void anUnknownTypeIsABadRequestProblem() throws Exception {
        assertProblem(
                http.postForEntity(SCREEN, Map.of("name", "Ivan Petrov", "type", "robot"), String.class), 400, "type");
    }

    @Test
    void anInvalidBirthDateIsABadRequestProblem() throws Exception {
        assertProblem(
                http.postForEntity(SCREEN, Map.of("name", "Ivan Petrov", "birthDate", "14/03/1971"), String.class),
                400,
                "birthDate");
    }

    @Test
    void noSanctionsDataIsServiceUnavailableNotAnEmptyResult() throws Exception {
        jdbc.update("DELETE FROM sanctions_dataset_version");

        assertProblem(http.postForEntity(SCREEN, Map.of("name", "Ivan Petrov"), String.class), 503, "sanctions");
    }

    private void assertProblem(ResponseEntity<String> response, int status, String detailMentions) throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = json.readTree(response.getBody());
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("detail").asText()).containsIgnoringCase(detailMentions);
    }
}
