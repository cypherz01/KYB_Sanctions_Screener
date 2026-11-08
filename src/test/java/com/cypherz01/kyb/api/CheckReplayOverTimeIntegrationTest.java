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
import com.cypherz01.kyb.support.MutableClock;
import com.cypherz01.kyb.support.MutableClockConfig;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M6: a replay judges a check as of when it ran. Rules that depend on the date, and data that was
 * stale at the time, must come out the same however long afterwards the replay happens.
 */
@ApiIntegrationTest
@Import(MutableClockConfig.class)
class CheckReplayOverTimeIntegrationTest {

    static final String CHECKS = "/api/v1/checks";
    static final String COMPANY = "07654321";

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

    @Autowired
    MutableClock clock;

    @BeforeEach
    void reset() {
        clock.set(MutableClockConfig.T0);
        wm.resetAll();
        cache.clearAll();
        jdbc.update("TRUNCATE kyb_check CASCADE");
        jdbc.update("DELETE FROM sanctions_dataset_version");
        OpenSanctionsStubs.publish(
                wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), "time-v1", "sanctions/entities-screening.ftm.json");
        assertThat(imports.importLatest().outcome()).isEqualTo(ImportOutcome.COMPLETED);
        stubCompany(Fixtures.read("checks/clean-profile.json"));
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/search-empty.json"))));
    }

    @Test
    void aCompanyRecentlyIncorporatedThenIsStillSoInTheReplay() throws Exception {
        LocalDate thirtyDaysOld =
                LocalDate.ofInstant(MutableClockConfig.T0, ZoneOffset.UTC).minusDays(30);
        stubCompany(Fixtures.read("checks/clean-profile.json").replace("2010-05-01", thirtyDaysOld.toString()));

        String stored = runCheck();
        assertThat(codes(json.readTree(stored))).containsExactly("COMPANY_RECENTLY_INCORPORATED");

        clock.advance(Duration.ofDays(730));
        JsonNode replay = assertReplaysIdentically(stored);

        assertThat(replay.path("replayedAt").asText())
                .isEqualTo(MutableClockConfig.T0.plus(Duration.ofDays(730)).toString());
        assertThat(codes(replay.path("result"))).containsExactly("COMPANY_RECENTLY_INCORPORATED");
    }

    @Test
    void staleCompaniesHouseDataIsStillFlaggedStaleInTheReplay() throws Exception {
        runCheck();
        clock.advance(Duration.ofHours(25)); // past the 24h TTL
        wm.stubFor(get(urlPathMatching("/company/.*")).willReturn(aResponse().withStatus(500)));

        String stored = runCheck();

        JsonNode companiesHouse = json.readTree(stored).path("sources").get(0);
        assertThat(companiesHouse.path("stale").asBoolean()).isTrue();
        assertThat(companiesHouse.path("fetchedAt").asText()).isEqualTo(MutableClockConfig.T0.toString());
        assertReplaysIdentically(stored);
    }

    @Test
    void staleRegisterDataIsStillFlaggedStaleInTheReplay() throws Exception {
        runCheck();
        clock.advance(Duration.ofDays(8)); // past the 7 day TTL
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(aResponse().withStatus(503)));

        String stored = runCheck();

        assertThat(json.readTree(stored).path("regulatory").path("availability").asText())
                .isEqualTo("STALE");
        assertReplaysIdentically(stored);
    }

    private JsonNode assertReplaysIdentically(String stored) throws Exception {
        JsonNode original = json.readTree(stored);
        ResponseEntity<String> response =
                http.postForEntity(CHECKS + "/" + original.path("checkId").asText() + "/replay", null, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode replay = json.readTree(response.getBody());
        assertThat(replay.path("identical").asBoolean())
                .as(replay.path("differences").toString())
                .isTrue();
        assertThat(replay.path("result")).isEqualTo(original);
        return replay;
    }

    private String runCheck() {
        ResponseEntity<String> response = http.postForEntity(CHECKS, Map.of("companyNumber", COMPANY), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static List<String> codes(JsonNode body) {
        List<String> codes = new ArrayList<>();
        body.path("factors").forEach(f -> codes.add(f.path("code").asText()));
        return codes;
    }

    private static void stubCompany(String profile) {
        String base = "/company/" + COMPANY;
        wm.stubFor(get(urlPathEqualTo(base)).willReturn(okJson(profile)));
        wm.stubFor(get(urlPathEqualTo(base + "/officers"))
                .willReturn(okJson(Fixtures.read("checks/clean-officers.json"))));
        wm.stubFor(get(urlPathEqualTo(base + "/persons-with-significant-control"))
                .willReturn(okJson(Fixtures.read("checks/clean-pscs.json"))));
        wm.stubFor(
                get(urlPathEqualTo(base + "/exemptions")).willReturn(aResponse().withStatus(404)));
    }
}
