package com.cypherz01.kyb.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.sanctions.ImportOutcome;
import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.support.ApiIntegrationTest;
import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.ArrayList;
import java.util.List;
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

/**
 * M5 acceptance, end to end: Companies House, the FCA Register and OpenSanctions are all WireMock
 * (one server, different paths). Postgres is real. A company with a sanctioned director FAILs with
 * the right factors; a clean company PASSes; the stored result is served unchanged.
 */
@ApiIntegrationTest
class CheckApiIntegrationTest {

    static final String CHECKS = "/api/v1/checks";
    static final String SANCTIONED = "01234567";
    static final String CLEAN = "07654321";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("kyb.companies-house.base-url", wm::baseUrl);
        registry.add("kyb.companies-house.api-key", () -> "it-ch-key");
        registry.add("kyb.companies-house.retry.base-delay", () -> "10ms");
        registry.add("kyb.companies-house.retry.max-delay", () -> "50ms");
        registry.add("kyb.fca.base-url", wm::baseUrl);
        registry.add("kyb.fca.auth-email", () -> "it@example.com");
        registry.add("kyb.fca.auth-key", () -> "it-fca-key");
        registry.add("kyb.sanctions.index-url", () -> wm.baseUrl() + OpenSanctionsStubs.INDEX_PATH);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    SanctionsImportService imports;

    @Autowired
    SourceCache cache;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void reset() {
        wm.resetAll();
        cache.clearAll();
        jdbc.update("TRUNCATE kyb_check CASCADE"); // checks are append-only: DELETE is rejected
        jdbc.update("DELETE FROM sanctions_dataset_version");
        OpenSanctionsStubs.publish(
                wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), "check-v1", "sanctions/entities-screening.ftm.json");
        assertThat(imports.importLatest().outcome()).isEqualTo(ImportOutcome.COMPLETED);
        stubCompany(SANCTIONED, "sanctioned");
        stubCompany(CLEAN, "clean");
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/search-empty.json"))));
    }

    // ---------- acceptance ----------

    @Test
    void aCompanyWithASanctionedDirectorFails() throws Exception {
        ResponseEntity<String> response = post(SANCTIONED);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("decision").asText()).isEqualTo("FAIL");
        assertThat(codes(body)).containsExactly("SANCTIONS_STRONG_MATCH");

        JsonNode factor = body.path("factors").get(0);
        assertThat(factor.path("autoFail").asBoolean()).isTrue();
        assertThat(factor.path("subject").path("name").asText()).isEqualTo("PETROV, Ivan");
        assertThat(factor.path("subject").path("roles"))
                .extracting(JsonNode::asText)
                .containsExactly("DIRECTOR", "PSC");
        assertThat(factor.path("subject").path("birthMonth").asText()).isEqualTo("1971-03");
        assertThat(factor.path("match").path("sanctionsEntityId").asText()).isEqualTo("NK-ivan");
        assertThat(factor.path("match").path("band").asText()).isEqualTo("STRONG");
        assertThat(factor.path("match").path("lists"))
                .extracting(JsonNode::asText)
                .contains("UK FCDO");
        assertThat(factor.path("match").path("evidence"))
                .extracting(JsonNode::asText)
                .anyMatch(e -> e.contains("Birth") && e.contains("1971"))
                .anyMatch(e -> e.contains("Nationality matches (RU)"));
        assertThat(factor.path("source").path("datasetVersion").asText()).isEqualTo("check-v1");
    }

    @Test
    void aCleanCompanyPasses() throws Exception {
        JsonNode body = json.readTree(post(CLEAN).getBody());

        assertThat(body.path("decision").asText()).isEqualTo("PASS");
        assertThat(body.path("score").asInt()).isZero();
        assertThat(body.path("factors").size()).isZero();
    }

    @Test
    void theResultDescribesTheCheck() throws Exception {
        JsonNode body = json.readTree(post(SANCTIONED).getBody());

        assertThat(body.path("checkId").asText()).isNotBlank();
        assertThat(body.path("companyNumber").asText()).isEqualTo(SANCTIONED);
        assertThat(body.path("companyName").asText()).isEqualTo("ACME WIDGETS LTD");
        assertThat(body.path("thresholds").path("review").asInt()).isEqualTo(40);
        assertThat(body.path("thresholds").path("fail").asInt()).isEqualTo(80);
        assertThat(body.path("scoringConfigVersion").asText()).isEqualTo("2026-11-09");
        assertThat(body.path("engineVersion").asText()).startsWith("2.0.0");
        assertThat(body.path("requestedAt").asText()).isNotBlank();
        assertThat(body.path("completedAt").asText()).isNotBlank();
        // company, the director-and-PSC once, the secretary
        assertThat(body.path("subjects"))
                .extracting(s -> s.path("name").asText())
                .containsExactly("ACME WIDGETS LTD", "PETROV, Ivan", "JONES, Sarah");
        assertThat(body.path("regulatory").path("availability").asText()).isEqualTo("AVAILABLE");

        List<String> sources = new ArrayList<>();
        body.path("sources").forEach(s -> sources.add(s.path("name").asText()));
        assertThat(sources).containsExactly("Companies House", "FCA Register", "OpenSanctions");
        JsonNode openSanctions = body.path("sources").get(2);
        assertThat(openSanctions.path("datasetVersion").asText()).isEqualTo("check-v1");
        assertThat(openSanctions.path("licence").asText()).isEqualTo("CC BY-NC 4.0");
        assertThat(body.path("sources").get(0).path("fetchedAt").asText()).isNotBlank();
    }

    @Test
    void theStoredCheckIsServedExactlyAsReturned() throws Exception {
        ResponseEntity<String> created = post(SANCTIONED);
        JsonNode original = json.readTree(created.getBody());
        String id = original.path("checkId").asText();
        assertThat(created.getHeaders().getLocation()).hasPath(CHECKS + "/" + id);

        ResponseEntity<String> fetched = http.getForEntity(CHECKS + "/" + id, String.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(fetched.getBody())).isEqualTo(original);
        // Byte for byte, not just the same JSON: field order must survive storage (jsonb re-sorts keys).
        assertThat(fetched.getBody()).isEqualTo(created.getBody());
    }

    @Test
    void theCheckIsRecordedWithWhatItWasRunAgainst() throws Exception {
        String id = json.readTree(post(SANCTIONED).getBody()).path("checkId").asText();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT company_number, decision, score, sanctions_dataset_version_id, scoring_config_version,"
                        + " engine_version, request->>'companyNumber' AS requested FROM kyb_check WHERE id = ?::uuid",
                id);
        Long active = jdbc.queryForObject("SELECT id FROM sanctions_dataset_version WHERE is_active", Long.class);

        assertThat(row.get("company_number")).isEqualTo(SANCTIONED);
        assertThat(row.get("decision")).isEqualTo("FAIL");
        assertThat(row.get("sanctions_dataset_version_id")).isEqualTo(active);
        assertThat(row.get("scoring_config_version")).isEqualTo("2026-11-09");
        assertThat((String) row.get("engine_version")).startsWith("2.0.0");
        assertThat(row.get("requested")).isEqualTo(SANCTIONED);
    }

    // ---------- other rules end to end ----------

    @Test
    void anFcaOutageStillProducesAResultWithTheUnavailableFactor() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(aResponse().withStatus(503)));

        ResponseEntity<String> response = post(CLEAN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json.readTree(response.getBody());
        assertThat(codes(body)).containsExactly("FCA_UNAVAILABLE");
        assertThat(body.path("decision").asText()).isEqualTo("PASS");
        assertThat(body.path("regulatory").path("availability").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void aRegulatedActivityWithoutAnAuthorisationIsFlagged() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/company/" + CLEAN))
                .willReturn(okJson(Fixtures.read("checks/clean-profile.json").replace("\"10710\"", "\"64921\""))));

        assertThat(codes(json.readTree(post(CLEAN).getBody()))).containsExactly("FCA_NOT_AUTHORISED_FOR_ACTIVITY");
    }

    @Test
    void anExemptCompanyWithoutPscsIsNotFlaggedButANonExemptOneIs() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/company/" + CLEAN + "/persons-with-significant-control"))
                .willReturn(okJson("{\"items\":[],\"total_results\":0}")));

        assertThat(codes(json.readTree(post(CLEAN).getBody()))).containsExactly("NO_PSC_DECLARED");

        cache.clearAll();
        wm.stubFor(get(urlPathEqualTo("/company/" + CLEAN + "/exemptions"))
                .willReturn(okJson(Fixtures.read("companieshouse/exemptions.json"))));
        JsonNode exempt = json.readTree(post(CLEAN).getBody());

        assertThat(codes(exempt)).isEmpty();
        assertThat(exempt.path("notes")).extracting(JsonNode::asText).anyMatch(n -> n.contains("exempt"));
    }

    // ---------- errors (RFC 7807) ----------

    @Test
    void anInvalidCompanyNumberIsABadRequest() throws Exception {
        assertProblem(post("NOT-A-NUMBER!"), 400);
    }

    @Test
    void aMissingCompanyNumberIsABadRequest() throws Exception {
        assertProblem(http.postForEntity(CHECKS, Map.of(), String.class), 400);
    }

    @Test
    void anUnreadableBodyIsABadRequest() throws Exception {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = http.postForEntity(
                CHECKS, new org.springframework.http.HttpEntity<>("{not json", headers), String.class);

        assertProblem(r, 400);
    }

    @Test
    void anUnknownCompanyIsNotFound() throws Exception {
        wm.stubFor(
                get(urlPathEqualTo("/company/09999999")).willReturn(aResponse().withStatus(404)));

        assertProblem(post("09999999"), 404);
    }

    @Test
    void companiesHouseDownWithNothingCachedIsServiceUnavailable() throws Exception {
        wm.stubFor(get(urlPathMatching("/company/.*")).willReturn(aResponse().withStatus(500)));

        assertProblem(post(CLEAN), 503);
    }

    @Test
    void noSanctionsDatasetIsServiceUnavailable() throws Exception {
        jdbc.update("DELETE FROM sanctions_dataset_version");

        assertProblem(post(CLEAN), 503);
    }

    @Test
    void anUnknownCheckIsNotFound() throws Exception {
        assertProblem(http.getForEntity(CHECKS + "/3f1c2b7e-0000-4000-8000-000000000000", String.class), 404);
    }

    @Test
    void aMalformedCheckIdIsABadRequest() throws Exception {
        assertProblem(http.getForEntity(CHECKS + "/not-a-uuid", String.class), 400);
    }

    // ---------- helpers ----------

    private ResponseEntity<String> post(String companyNumber) {
        return http.postForEntity(CHECKS, Map.of("companyNumber", companyNumber), String.class);
    }

    private static List<String> codes(JsonNode body) {
        List<String> codes = new ArrayList<>();
        body.path("factors").forEach(f -> codes.add(f.path("code").asText()));
        return codes;
    }

    private void assertProblem(ResponseEntity<String> response, int status) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = json.readTree(response.getBody());
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("detail").asText()).isNotBlank();
    }

    private static void stubCompany(String number, String fixture) {
        String base = "/company/" + number;
        wm.stubFor(get(urlPathEqualTo(base)).willReturn(okJson(Fixtures.read("checks/" + fixture + "-profile.json"))));
        wm.stubFor(get(urlPathEqualTo(base + "/officers"))
                .willReturn(okJson(Fixtures.read("checks/" + fixture + "-officers.json"))));
        wm.stubFor(get(urlPathEqualTo(base + "/persons-with-significant-control"))
                .willReturn(okJson(Fixtures.read("checks/" + fixture + "-pscs.json"))));
        wm.stubFor(
                get(urlPathEqualTo(base + "/exemptions")).willReturn(aResponse().withStatus(404)));
    }
}
