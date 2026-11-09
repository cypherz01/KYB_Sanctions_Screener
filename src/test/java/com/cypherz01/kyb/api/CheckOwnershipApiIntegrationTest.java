package com.cypherz01.kyb.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M8 acceptance, end to end: the real application walking ownership chains served by WireMock as
 * Companies House, screening against the fixture list. Every company here is invented.
 */
@ApiIntegrationTest
class CheckOwnershipApiIntegrationTest {

    static final String CHECKS = "/api/v1/checks";
    static final String FIXTURE = "sanctions/entities-screening.ftm.json";

    static final String TARGET = "07654321";
    static final String HOLDCO = "20000001";
    static final String TOPCO = "20000002";
    static final String OTHER = "20000003";

    static final String BAND_25 = "ownership-of-shares-25-to-50-percent";
    static final String BAND_50 = "ownership-of-shares-50-to-75-percent";
    static final String BAND_75 = "ownership-of-shares-75-to-100-percent";
    static final String APPOINT = "right-to-appoint-and-remove-directors";
    static final String INFLUENCE = "significant-influence-or-control";

    /** On the fixture list with this birth month and nationality, so a certain STRONG match. */
    static final String SANCTIONED = person("Mr Ivan Petrov", 1971, 3, "Russian", BAND_75);

    static final String CLEAN = person("Mrs Sarah Jones", 1980, 5, "British", BAND_75);

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
        jdbc.update("TRUNCATE kyb_check CASCADE");
        jdbc.update("DELETE FROM sanctions_dataset_version");
        byte[] body = Fixtures.bytes(FIXTURE);
        OpenSanctionsStubs.publish(
                wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), "own-v1", body, OpenSanctionsStubs.sha1(body));
        assertThat(imports.importLatest().outcome()).isEqualTo(ImportOutcome.COMPLETED);
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/search-empty.json"))));
    }

    // ---------- the decision rule ----------

    @Test
    void aSanctionedMajorityOwnerTwoLevelsUpFailsTheCompany() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", SANCTIONED);

        JsonNode check = check(TARGET);

        JsonNode factor = factor(check, "SANCTIONS_STRONG_MATCH");
        assertThat(check.path("decision").asText()).isEqualTo("FAIL");
        assertThat(factor.path("autoFail").asBoolean()).isTrue();
        assertThat(factor.path("ownership").path("lowerPercent").asDouble()).isEqualTo(56.25);
        assertThat(factor.path("ownership").path("upperPercent").asDouble()).isEqualTo(100.0);
        assertThat(subject(check, "Mr Ivan Petrov").path("roles").toString()).contains("UPSTREAM_PSC");
    }

    @Test
    void aSanctionedOwnerWhoMightHoldHalfSendsTheCompanyToReviewWithTheCalculation() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_50));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", SANCTIONED);

        JsonNode check = check(TARGET);

        JsonNode factor = factor(check, "SANCTIONS_STRONG_MATCH");
        assertThat(check.path("decision").asText()).isEqualTo("REVIEW");
        assertThat(factor.path("autoFail").asBoolean()).isFalse();
        assertThat(factor.path("ownership").path("lowerPercent").asDouble()).isEqualTo(37.5);
        assertThat(factor.path("ownership").path("upperPercent").asDouble()).isEqualTo(75.0);
    }

    @Test
    void aSanctionedOwnerControllingThroughTheChainFailsTheCompany() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, APPOINT));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", person("Mr Ivan Petrov", 1971, 3, "Russian", APPOINT));

        JsonNode check = check(TARGET);

        assertThat(check.path("decision").asText()).isEqualTo("FAIL");
        assertThat(factor(check, "SANCTIONS_STRONG_MATCH")
                        .path("ownership")
                        .path("control")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void aSanctionedPersonWithOnlySignificantInfluenceSendsTheCompanyToReview() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", person("Mr Ivan Petrov", 1971, 3, "Russian", INFLUENCE));

        assertThat(check(TARGET).path("decision").asText()).isEqualTo("REVIEW");
    }

    @Test
    void holdingsThroughSeparateParentsAddUp() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_50), company("OAKWELL HOLDINGS LTD", OTHER, BAND_25));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", SANCTIONED);
        parent(OTHER, "OAKWELL HOLDINGS LTD", SANCTIONED);

        JsonNode check = check(TARGET);

        // 37.5-75 through the first, 18.75-50 through the second
        JsonNode factor = factor(check, "SANCTIONS_STRONG_MATCH");
        assertThat(check.path("decision").asText()).isEqualTo("FAIL");
        assertThat(factor.path("ownership").path("lowerPercent").asDouble()).isEqualTo(56.25);
        assertThat(factor.path("ownership").path("upperPercent").asDouble()).isEqualTo(100.0);
    }

    @Test
    void aSanctionedIntermediateCompanyIsScreenedAndJudgedOnItsStake() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", company("ZARYA HOLDINGS LTD", TOPCO, BAND_25));
        parent(TOPCO, "ZARYA HOLDINGS LTD", CLEAN);

        JsonNode check = check(TARGET);

        // 18.75-50: the upper bound reaches half
        assertThat(check.path("decision").asText()).isEqualTo("REVIEW");
        assertThat(factor(check, "SANCTIONS_STRONG_MATCH")
                        .path("match")
                        .path("sanctionsEntityId")
                        .asText())
                .isEqualTo("NK-unlabelled");
        assertThat(subject(check, "ZARYA HOLDINGS LTD").path("roles").toString())
                .contains("INTERMEDIATE_COMPANY");
    }

    /** Ownership never excuses someone already at the first level. */
    @Test
    void aSanctionedDirectPscStillFailsWithASmallStake() throws Exception {
        target(person("Mr Ivan Petrov", 1971, 3, "Russian", BAND_25));

        JsonNode check = check(TARGET);

        assertThat(check.path("decision").asText()).isEqualTo("FAIL");
        assertThat(factor(check, "SANCTIONS_STRONG_MATCH").path("autoFail").asBoolean())
                .isTrue();
    }

    @Test
    void someoneBothDirectAndUpstreamIsTreatedAsDirect() throws Exception {
        target(person("Mr Ivan Petrov", 1971, 3, "Russian", BAND_25), company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_25));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", person("Mr Ivan Petrov", 1971, 3, "Russian", BAND_25));

        JsonNode check = check(TARGET);

        assertThat(check.path("decision").asText()).isEqualTo("FAIL");
        assertThat(factors(check, "SANCTIONS_STRONG_MATCH")).hasSize(1);
        assertThat(factor(check, "SANCTIONS_STRONG_MATCH").path("autoFail").asBoolean())
                .isTrue();
    }

    @Test
    void aPossibleMatchUpstreamScoresWithItsBoundsInTheEvidence() throws Exception {
        String similar = "Ivana Petrova";
        ResponseEntity<String> screened =
                http.postForEntity("/api/v1/screen/name", Map.of("name", similar, "type", "PERSON"), String.class);
        assertThat(json.readTree(screened.getBody())
                        .path("matches")
                        .get(0)
                        .path("band")
                        .asText())
                .as("this test relies on the name being POSSIBLE on the fixture list")
                .isEqualTo("POSSIBLE");
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_25));
        parent(
                HOLDCO,
                "HARBOUR HOLDINGS LTD",
                """
                {"name":"%s","kind":"individual-person-with-significant-control","notified_on":"2016-06-30",
                 "natures_of_control":["%s"]}
                """
                        .formatted(similar, BAND_25));

        JsonNode check = check(TARGET);

        JsonNode factor = factor(check, "SANCTIONS_POSSIBLE_MATCH");
        assertThat(factor.path("points").asInt()).isGreaterThanOrEqualTo(35);
        assertThat(factor.path("ownership").path("lowerPercent").asDouble()).isEqualTo(6.25);
        assertThat(factor.path("ownership").path("upperPercent").asDouble()).isEqualTo(25.0);
    }

    // ---------- what gets followed ----------

    @Test
    void anUpperCompanyAndItsPscsAreScreenedButNotItsOfficers() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", CLEAN);
        // If this were read and screened, the company would fail.
        wm.stubFor(get(urlPathEqualTo("/company/" + HOLDCO + "/officers"))
                .willReturn(okJson(Fixtures.read("checks/sanctioned-officers.json"))));

        JsonNode check = check(TARGET);

        assertThat(check.path("decision").asText()).isEqualTo("PASS");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/company/" + HOLDCO + "/officers")));
    }

    @Test
    void aCeasedCorporatePscIsNotFollowed() throws Exception {
        target(
                CLEAN,
                """
                {"name":"OLD OWNER LTD","kind":"corporate-entity-person-with-significant-control",
                 "notified_on":"2016-06-30","ceased_on":"2020-01-01","natures_of_control":["%s"],
                 "identification":{"country_registered":"England","registration_number":"%s"}}
                """
                        .formatted(BAND_75, HOLDCO));

        check(TARGET);

        wm.verify(0, getRequestedFor(urlPathMatching("/company/" + HOLDCO + ".*")));
    }

    @Test
    void aNumberWithoutItsLeadingZeroIsStillFollowed() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", "1234567", BAND_75));
        parent("01234567", "HARBOUR HOLDINGS LTD", CLEAN);

        JsonNode check = check(TARGET);

        assertThat(codes(check)).doesNotContain("OPAQUE_OWNERSHIP");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/company/01234567")));
    }

    // ---------- where a chain stops ----------

    @Test
    void aForeignOwnerIsOpaqueAndIsNotLookedUp() throws Exception {
        target(foreignCompany("OFFSHORE HOLDINGS S.A.", "Panama", "155555555", BAND_75));

        JsonNode check = check(TARGET);

        assertThat(codes(check)).contains("OPAQUE_OWNERSHIP", "CORPORATE_PSC_OFFSHORE");
        assertThat(check.path("score").asInt()).isEqualTo(25);
        assertThat(check.path("decision").asText()).isEqualTo("PASS");
        assertThat(stopOf(check, "OFFSHORE HOLDINGS S.A.").path("reason").asText())
                .isEqualTo("FOREIGN");
        wm.verify(0, getRequestedFor(urlPathMatching("/company/155555555.*")));
    }

    @Test
    void aUkOwnerWithAnUnusableNumberIsOpaque() throws Exception {
        target(foreignCompany("VAGUE HOLDINGS LTD", "England", "see register", BAND_75));

        JsonNode check = check(TARGET);

        assertThat(codes(check)).contains("OPAQUE_OWNERSHIP");
        assertThat(stopOf(check, "VAGUE HOLDINGS LTD").path("reason").asText()).isEqualTo("UNRESOLVED");
    }

    @Test
    void anOwnerWithNoPublishedCountryIsOpaque() throws Exception {
        target(foreignCompany("MYSTERY HOLDINGS LTD", null, HOLDCO, BAND_75));

        JsonNode check = check(TARGET);

        assertThat(codes(check)).contains("OPAQUE_OWNERSHIP");
        wm.verify(0, getRequestedFor(urlPathMatching("/company/" + HOLDCO + ".*")));
    }

    @Test
    void aLegalPersonWithNoCompanyNumberIsOpaque() throws Exception {
        target(
                """
                {"name":"SOME PUBLIC BODY","kind":"legal-person-person-with-significant-control",
                 "notified_on":"2016-06-30","natures_of_control":["%s"],"identification":{"legal_form":"Public body"}}
                """
                        .formatted(BAND_75));

        assertThat(codes(check(TARGET))).contains("OPAQUE_OWNERSHIP");
    }

    @Test
    void anOwnerCompaniesHouseDoesNotKnowIsOpaqueAndReplaysIdentically() throws Exception {
        target(company("GHOST HOLDINGS LTD", HOLDCO, BAND_75));
        wm.stubFor(get(urlPathMatching("/company/" + HOLDCO + ".*"))
                .willReturn(aResponse().withStatus(404)));

        String stored = run(TARGET);
        JsonNode check = json.readTree(stored);

        assertThat(codes(check)).contains("OPAQUE_OWNERSHIP");
        assertThat(stopOf(check, "GHOST HOLDINGS LTD").path("reason").asText()).isEqualTo("UNRESOLVED");
        assertReplaysIdenticallyWithNoUpstream(stored);
    }

    @Test
    void anOutageAtAParentIsOpaqueDoesNotStopTheCheckAndReplaysIdentically() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        wm.stubFor(get(urlPathMatching("/company/" + HOLDCO + ".*"))
                .willReturn(aResponse().withStatus(503)));

        String stored = run(TARGET);
        JsonNode check = json.readTree(stored);

        assertThat(codes(check)).contains("OPAQUE_OWNERSHIP");
        assertThat(stopOf(check, "HARBOUR HOLDINGS LTD").path("reason").asText())
                .isEqualTo("UNAVAILABLE");
        assertReplaysIdenticallyWithNoUpstream(stored);
    }

    @Test
    void aParentDeclaringNoPscsAndNoExemptionIsOpaque() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD");

        JsonNode check = check(TARGET);

        assertThat(codes(check)).contains("OPAQUE_OWNERSHIP");
        assertThat(stopOf(check, "HARBOUR HOLDINGS LTD").path("reason").asText())
                .isEqualTo("NO_PSCS");
    }

    /** Listed companies declare no PSCs by law, so every listed group would otherwise look opaque. */
    @Test
    void anExemptParentIsACleanEnd() throws Exception {
        target(company("LISTED GROUP PLC", HOLDCO, BAND_75));
        parent(HOLDCO, "LISTED GROUP PLC");
        wm.stubFor(
                get(urlPathEqualTo("/company/" + HOLDCO + "/exemptions"))
                        .willReturn(
                                okJson(
                                        """
                {"exemptions":{"psc_exempt_as_trading_on_uk_regulated_market":{
                  "exemption_type":"psc-exempt-as-trading-on-uk-regulated-market","items":[{"exempt_from":"2016-06-30"}]}}}
                """)));

        JsonNode check = check(TARGET);

        assertThat(codes(check)).doesNotContain("OPAQUE_OWNERSHIP");
        assertThat(check.path("decision").asText()).isEqualTo("PASS");
        assertThat(check.path("notes").toString()).contains("LISTED GROUP PLC");
        assertThat(stopOf(check, "LISTED GROUP PLC").path("reason").asText()).isEqualTo("EXEMPT");
    }

    @Test
    void aSuperSecureOwnerUpstreamIsOpaque() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(
                HOLDCO,
                "HARBOUR HOLDINGS LTD",
                "{\"kind\":\"super-secure-person-with-significant-control\",\"description\":\"super-secure-persons-with-significant-control\"}");

        JsonNode check = check(TARGET);

        assertThat(codes(check)).contains("OPAQUE_OWNERSHIP");
    }

    /** v1 behaviour, unchanged: a note, because a super-secure PSC is a declared PSC. */
    @Test
    void aSuperSecureDirectPscStaysANote() throws Exception {
        target(
                "{\"kind\":\"super-secure-person-with-significant-control\",\"description\":\"super-secure-persons-with-significant-control\"}");

        JsonNode check = check(TARGET);

        assertThat(codes(check)).doesNotContain("OPAQUE_OWNERSHIP");
        assertThat(check.path("notes").toString()).containsIgnoringCase("super-secure");
    }

    @Test
    void aLoopBetweenParentsIsCircularAndEnds() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", company("TOPCO HOLDINGS LTD", TOPCO, BAND_75));
        parent(TOPCO, "TOPCO HOLDINGS LTD", company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));

        JsonNode check = check(TARGET);

        JsonNode factor = factor(check, "CIRCULAR_OWNERSHIP");
        assertThat(factor.path("points").asInt()).isEqualTo(25);
        assertThat(check.path("decision").asText()).isEqualTo("PASS");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/company/" + HOLDCO)));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/company/" + TOPCO)));
    }

    @Test
    void aParentOwnedByTheCompanyItselfIsCircular() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", company("BRIGHT BAKERY LTD", TARGET, BAND_75));

        JsonNode check = check(TARGET);

        assertThat(codes(check)).contains("CIRCULAR_OWNERSHIP");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/company/" + TARGET)));
    }

    @Test
    void aChainDeeperThanFiveStopsAtTheLimitAndSaysSo() throws Exception {
        target(company("LEVEL 1 LTD", level(1), BAND_75));
        for (int i = 1; i <= 5; i++) {
            parent(level(i), "LEVEL " + i + " LTD", company("LEVEL " + (i + 1) + " LTD", level(i + 1), BAND_75));
        }
        parent(level(6), "LEVEL 6 LTD", SANCTIONED);

        JsonNode check = check(TARGET);

        assertThat(factor(check, "OWNERSHIP_LIMIT_REACHED").path("points").asInt())
                .isEqualTo(15);
        assertThat(stopOf(check, "LEVEL 6 LTD").path("reason").asText()).isEqualTo("DEPTH_LIMIT");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/company/" + level(5))));
        wm.verify(0, getRequestedFor(urlPathMatching("/company/" + level(6) + ".*")));
        // The sixth company is named by the fifth, so it is still screened. Only its owners are unseen.
        assertThat(subject(check, "LEVEL 6 LTD").isMissingNode()).isFalse();
        assertThat(subject(check, "Mr Ivan Petrov").isMissingNode()).isTrue();
    }

    @Test
    void aCheckFetchesAtMostTwentyFiveCompaniesAndSaysSoWhenItStops() throws Exception {
        List<String> owners = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            String number = String.format("%08d", 40000000 + i);
            owners.add(company("PARENT " + i + " LTD", number, BAND_25));
            parent(number, "PARENT " + i + " LTD", CLEAN);
        }
        target(owners.toArray(new String[0]));

        JsonNode check = check(TARGET);

        assertThat(codes(check)).contains("OWNERSHIP_LIMIT_REACHED");
        assertThat(wm.findAll(getRequestedFor(urlPathMatching("/company/4000\\d{4}"))))
                .hasSize(25);
    }

    // ---------- found by checking real companies and by code review ----------

    /** HSBC Holdings is published with the country "Gbr" and a place of "United Kingdom". */
    @Test
    void aUkOwnerWhoseCountryIsGivenAsGbrIsFollowed() throws Exception {
        target(foreignCompany("HARBOUR HOLDINGS LTD", "Gbr", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", CLEAN);

        JsonNode check = check(TARGET);

        assertThat(codes(check)).doesNotContain("OPAQUE_OWNERSHIP", "CORPORATE_PSC_OFFSHORE");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/company/" + HOLDCO)));
    }

    /** A charity's number, say, can be padded into the number of a company with no link to it. */
    @Test
    void aNumberBelongingToAnUnrelatedCompanyIsOpaqueAndItsOwnersAreNotScreened() throws Exception {
        target(company("ST MARYS HOSPICE TRUST", HOLDCO, BAND_75));
        parent(HOLDCO, "UNRELATED ENGINEERING LTD", SANCTIONED);

        String stored = run(TARGET);
        JsonNode check = json.readTree(stored);

        assertThat(check.path("decision").asText()).isEqualTo("PASS");
        assertThat(codes(check)).contains("OPAQUE_OWNERSHIP");
        JsonNode stop = stopOf(check, "ST MARYS HOSPICE TRUST");
        assertThat(stop.path("reason").asText()).isEqualTo("UNRESOLVED");
        assertThat(stop.path("detail").asText()).contains("UNRELATED ENGINEERING LTD");
        assertThat(subject(check, "Mr Ivan Petrov").isMissingNode()).isTrue();
        assertReplaysIdenticallyWithNoUpstream(stored);
    }

    @Test
    void aSmallDifferenceInTheNameIsStillTheSameCompany() throws Exception {
        target(company("HARBOUR HOLDING LIMITED", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", SANCTIONED);

        JsonNode check = check(TARGET);

        assertThat(codes(check)).doesNotContain("OPAQUE_OWNERSHIP");
        assertThat(check.path("decision").asText()).isEqualTo("FAIL");
    }

    @Test
    void anUpperCompanysRegisteredNameIsScreenedAsWellAsTheNameOnThePscEntry() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", company("ZARYA HOLDING LIMITED", TOPCO, BAND_75));
        parent(TOPCO, "ZARYA HOLDINGS LTD", CLEAN);

        JsonNode check = check(TARGET);

        JsonNode registered = subject(check, "ZARYA HOLDINGS LTD");
        assertThat(registered.isMissingNode()).isFalse();
        assertThat(registered.path("matches").size()).isPositive();
        assertThat(registered.path("roles").toString()).contains("INTERMEDIATE_COMPANY");
    }

    /** The name a direct parent files under is as much at the first level as its PSC entry. */
    @Test
    void aDirectParentsRegisteredNameIsTreatedAsDirect() throws Exception {
        target(company("ZARYA HOLDING LIMITED", HOLDCO, BAND_25));
        parent(HOLDCO, "ZARYA HOLDINGS LTD", CLEAN);

        JsonNode check = check(TARGET);

        assertThat(subject(check, "ZARYA HOLDINGS LTD").isMissingNode()).isFalse();
        assertThat(check.path("decision").asText()).isEqualTo("FAIL");
        assertThat(factors(check, "SANCTIONS_STRONG_MATCH"))
                .allSatisfy(f -> assertThat(f.path("autoFail").asBoolean()).isTrue());
    }

    /** Exemptions only matter when a parent declares nobody, so losing them must not lose owners. */
    @Test
    void anExemptionsOutageDoesNotHideOwnersWhoLoaded() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", SANCTIONED);
        wm.stubFor(get(urlPathEqualTo("/company/" + HOLDCO + "/exemptions"))
                .willReturn(aResponse().withStatus(503)));

        String stored = run(TARGET);
        JsonNode check = json.readTree(stored);

        assertThat(check.path("decision").asText()).isEqualTo("FAIL");
        assertThat(codes(check)).doesNotContain("OPAQUE_OWNERSHIP");
        assertReplaysIdenticallyWithNoUpstream(stored);
    }

    @Test
    void anExemptionsOutageAtAParentDeclaringNobodyIsOpaque() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD");
        wm.stubFor(get(urlPathEqualTo("/company/" + HOLDCO + "/exemptions"))
                .willReturn(aResponse().withStatus(503)));

        String stored = run(TARGET);
        JsonNode check = json.readTree(stored);

        assertThat(stopOf(check, "HARBOUR HOLDINGS LTD").path("reason").asText())
                .isEqualTo("UNAVAILABLE");
        assertReplaysIdenticallyWithNoUpstream(stored);
    }

    /** Without a profile the name cannot be checked, but owners who loaded are still screened. */
    @Test
    void aProfileOutageDoesNotHideOwnersWhoLoaded() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", SANCTIONED);
        wm.stubFor(
                get(urlPathEqualTo("/company/" + HOLDCO)).willReturn(aResponse().withStatus(503)));

        String stored = run(TARGET);
        JsonNode check = json.readTree(stored);

        assertThat(check.path("decision").asText()).isEqualTo("FAIL");
        assertThat(check.path("notes").toString()).contains("HARBOUR HOLDINGS LTD");
        assertReplaysIdenticallyWithNoUpstream(stored);
    }

    @Test
    void everyNationalityPublishedForAnUpstreamOwnerIsCompared() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75), company("OAKWELL HOLDINGS LTD", OTHER, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", person("Mr Ivan Petrov", 1971, 3, "British", BAND_75));
        parent(OTHER, "OAKWELL HOLDINGS LTD", person("Mr Ivan Petrov", 1971, 3, "Russian", BAND_75));

        JsonNode check = check(TARGET);

        assertThat(factor(check, "SANCTIONS_STRONG_MATCH")
                        .path("match")
                        .path("evidence")
                        .toString())
                .contains("Nationality matches (RU)");
    }

    /** One name in two countries is two companies: stakes must not add, and both chains are listed. */
    @Test
    void foreignOwnersWithTheSameNameInDifferentCountriesStaySeparate() throws Exception {
        target(
                foreignCompany("GLOBEX INC", "Panama", "1", BAND_25),
                foreignCompany("GLOBEX INC", "Cyprus", "2", BAND_25));

        JsonNode check = check(TARGET);

        assertThat(names(check.path("ownership")))
                .filteredOn("GLOBEX INC"::equals)
                .hasSize(2);
        assertThat(check.path("ownership").path("nodes"))
                .filteredOn(n -> n.path("name").asText().equals("GLOBEX INC"))
                .allSatisfy(
                        n -> assertThat(n.path("effective").path("upperPercent").asDouble())
                                .isEqualTo(50.0));
        assertThat(factor(check, "OPAQUE_OWNERSHIP").path("detail").asText())
                .contains("Panama")
                .contains("Cyprus");
    }

    @Test
    void anUnknownNatureIsNotedEvenWhenAnotherOnTheSamePscIsUnderstood() throws Exception {
        target(
                """
                {"name":"Mrs Sarah Jones","kind":"individual-person-with-significant-control","nationality":"British",
                 "date_of_birth":{"month":5,"year":1980},"notified_on":"2016-06-30",
                 "natures_of_control":["%s","a-nature-nobody-has-seen"]}
                """
                        .formatted(BAND_75));

        assertThat(check(TARGET).path("notes").toString()).contains("a-nature-nobody-has-seen");
    }

    // ---------- the response and the record ----------

    @Test
    void theResponseCarriesTheOwnershipTree() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", CLEAN, foreignCompany("OFFSHORE S.A.", "Panama", "1", BAND_25));

        JsonNode ownership = check(TARGET).path("ownership");

        assertThat(ownership.path("performed").asBoolean()).isTrue();
        assertThat(names(ownership))
                .containsExactlyInAnyOrder(
                        "BRIGHT BAKERY LTD", "HARBOUR HOLDINGS LTD", "Mrs Sarah Jones", "OFFSHORE S.A.");

        JsonNode holdco = node(ownership, "HARBOUR HOLDINGS LTD");
        assertThat(holdco.path("type").asText()).isEqualTo("COMPANY");
        assertThat(holdco.path("companyNumber").asText()).isEqualTo(HOLDCO);
        assertThat(holdco.path("effective").path("lowerPercent").asDouble()).isEqualTo(75.0);

        JsonNode offshore = node(ownership, "OFFSHORE S.A.");
        assertThat(offshore.path("effective").path("lowerPercent").asDouble()).isEqualTo(18.75);
        assertThat(offshore.path("effective").path("upperPercent").asDouble()).isEqualTo(50.0);
        assertThat(offshore.path("stop").path("reason").asText()).isEqualTo("FOREIGN");
        assertThat(offshore.path("stop").path("detail").asText()).contains("Panama");

        JsonNode link =
                link(ownership, offshore.path("id").asText(), holdco.path("id").asText());
        assertThat(link.path("natures").toString()).contains(BAND_25);
        assertThat(link.path("lowerPercent").asDouble()).isEqualTo(25.0);
        assertThat(link.path("upperPercent").asDouble()).isEqualTo(50.0);
        assertThat(link.path("kind").asText()).isEqualTo("OWNERSHIP");
    }

    @Test
    void aCompanyOwnedOnlyByPeopleHasASimpleTree() throws Exception {
        target(CLEAN);

        JsonNode ownership = check(TARGET).path("ownership");

        assertThat(ownership.path("performed").asBoolean()).isTrue();
        assertThat(names(ownership)).containsExactlyInAnyOrder("BRIGHT BAKERY LTD", "Mrs Sarah Jones");
        assertThat(ownership.path("links")).hasSize(1);
    }

    @Test
    void eachParentIsSnapshottedSoALookThroughCheckReplaysWithNoUpstreamCalls() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", SANCTIONED);

        String stored = run(TARGET);

        assertThat(operations(json.readTree(stored).path("checkId").asText())).anyMatch(o -> o.contains(HOLDCO));
        assertReplaysIdenticallyWithNoUpstream(stored);
    }

    @Test
    void aCheckServedFromTheCacheStillSnapshotsItsParents() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", SANCTIONED);
        run(TARGET);

        String second = run(TARGET);

        wm.verify(1, getRequestedFor(urlPathEqualTo("/company/" + HOLDCO)));
        assertReplaysIdenticallyWithNoUpstream(second);
    }

    // ---------- replaying a check made before look-through ----------

    @Test
    void aCheckMadeBeforeLookThroughReplaysAtDepthZeroAndReportsTheDifference() throws Exception {
        target(company("HARBOUR HOLDINGS LTD", HOLDCO, BAND_75));
        parent(HOLDCO, "HARBOUR HOLDINGS LTD", CLEAN);
        String legacy = asIfMadeBeforeLookThrough(
                json.readTree(run(TARGET)).path("checkId").asText());
        wm.resetAll();
        cache.clearAll();

        ResponseEntity<String> response = http.postForEntity(CHECKS + "/" + legacy + "/replay", null, String.class);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode replay = json.readTree(response.getBody());
        JsonNode result = replay.path("result");
        assertThat(replay.path("identical").asBoolean()).isFalse();
        assertThat(replay.path("original").path("engineVersion").asText()).startsWith("1.0.0");
        assertThat(replay.path("replayed").path("engineVersion").asText()).startsWith("2.1.0");
        assertThat(paths(replay)).contains("engineVersion").anyMatch(p -> p.startsWith("ownership"));
        assertThat(result.path("notes").toString()).contains("ownership look-through not performed at check time");
        assertThat(result.path("ownership").path("performed").asBoolean()).isFalse();
        // The parents were never looked up, which is not the same as there being a gap to score.
        assertThat(codes(result)).doesNotContain("OPAQUE_OWNERSHIP", "OWNERSHIP_LIMIT_REACHED", "CIRCULAR_OWNERSHIP");
        assertThat(subject(result, "Mrs Sarah Jones").path("roles").toString()).doesNotContain("UPSTREAM_PSC");
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    // ---------- helpers ----------

    private static String level(int i) {
        return String.format("%08d", 30000000 + i);
    }

    /** The company being checked: clean in itself, with the given owners. */
    private static void target(String... pscs) {
        stub(TARGET, "BRIGHT BAKERY LTD", pscs);
        wm.stubFor(get(urlPathEqualTo("/company/" + TARGET + "/officers"))
                .willReturn(okJson(Fixtures.read("checks/clean-officers.json"))));
    }

    private static void parent(String number, String name, String... pscs) {
        stub(number, name, pscs);
    }

    private static void stub(String number, String name, String... pscs) {
        String base = "/company/" + number;
        wm.stubFor(get(urlPathEqualTo(base))
                .willReturn(okJson(Fixtures.read("checks/clean-profile.json")
                        .replace("07654321", number)
                        .replace("BRIGHT BAKERY LTD", name))));
        wm.stubFor(get(urlPathEqualTo(base + "/officers")).willReturn(okJson("{\"items\":[],\"total_results\":0}")));
        wm.stubFor(get(urlPathEqualTo(base + "/persons-with-significant-control"))
                .willReturn(
                        okJson("{\"items\":[" + String.join(",", pscs) + "],\"total_results\":" + pscs.length + "}")));
        wm.stubFor(
                get(urlPathEqualTo(base + "/exemptions")).willReturn(aResponse().withStatus(404)));
    }

    private static String person(String name, int year, int month, String nationality, String nature) {
        return """
                {"name":"%s","kind":"individual-person-with-significant-control","nationality":"%s",
                 "date_of_birth":{"month":%d,"year":%d},"notified_on":"2016-06-30","natures_of_control":["%s"]}
                """
                .formatted(name, nationality, month, year, nature);
    }

    private static String company(String name, String number, String nature) {
        return foreignCompany(name, "England", number, nature);
    }

    private static String foreignCompany(String name, String country, String number, String nature) {
        String registered = country == null ? "" : "\"country_registered\":\"" + country + "\",";
        return """
                {"name":"%s","kind":"corporate-entity-person-with-significant-control","notified_on":"2016-06-30",
                 "natures_of_control":["%s"],"identification":{%s"registration_number":"%s"}}
                """
                .formatted(name, nature, registered, number);
    }

    private String run(String number) {
        ResponseEntity<String> response = http.postForEntity(CHECKS, Map.of("companyNumber", number), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private JsonNode check(String number) throws Exception {
        return json.readTree(run(number));
    }

    private static List<String> codes(JsonNode check) {
        List<String> codes = new ArrayList<>();
        check.path("factors").forEach(f -> codes.add(f.path("code").asText()));
        return codes;
    }

    private static List<JsonNode> factors(JsonNode check, String code) {
        List<JsonNode> matching = new ArrayList<>();
        check.path("factors").forEach(f -> {
            if (f.path("code").asText().equals(code)) {
                matching.add(f);
            }
        });
        return matching;
    }

    private static JsonNode factor(JsonNode check, String code) {
        List<JsonNode> matching = factors(check, code);
        assertThat(matching).as(code + " in " + check.path("factors")).isNotEmpty();
        return matching.get(0);
    }

    private static JsonNode subject(JsonNode check, String name) {
        for (JsonNode s : check.path("subjects")) {
            if (s.path("name").asText().equals(name)) {
                return s;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static JsonNode node(JsonNode ownership, String name) {
        for (JsonNode n : ownership.path("nodes")) {
            if (n.path("name").asText().equals(name)) {
                return n;
            }
        }
        throw new AssertionError("No node " + name + " in " + ownership);
    }

    private static List<String> names(JsonNode ownership) {
        List<String> names = new ArrayList<>();
        ownership.path("nodes").forEach(n -> names.add(n.path("name").asText()));
        return names;
    }

    private static JsonNode link(JsonNode ownership, String owner, String owned) {
        for (JsonNode l : ownership.path("links")) {
            if (l.path("owner").asText().equals(owner)
                    && l.path("owned").asText().equals(owned)) {
                return l;
            }
        }
        throw new AssertionError("No link " + owner + " -> " + owned + " in " + ownership);
    }

    private static JsonNode stopOf(JsonNode check, String name) {
        return node(check.path("ownership"), name).path("stop");
    }

    private List<String> operations(String checkId) throws Exception {
        JsonNode trail = json.readTree(http.getForEntity(CHECKS + "/" + checkId + "/audit", String.class)
                .getBody());
        List<String> operations = new ArrayList<>();
        trail.path("snapshots").forEach(s -> operations.add(s.path("operation").asText()));
        return operations;
    }

    private static List<String> paths(JsonNode replay) {
        List<String> paths = new ArrayList<>();
        replay.path("differences").forEach(d -> paths.add(d.path("path").asText()));
        return paths;
    }

    private void assertReplaysIdenticallyWithNoUpstream(String stored) throws Exception {
        JsonNode original = json.readTree(stored);
        wm.resetAll();
        cache.clearAll();

        ResponseEntity<String> response =
                http.postForEntity(CHECKS + "/" + original.path("checkId").asText() + "/replay", null, String.class);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode replay = json.readTree(response.getBody());
        assertThat(replay.path("identical").asBoolean()).as(replay.toString()).isTrue();
        assertThat(replay.path("result")).isEqualTo(original);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * A copy of a check as M7 would have stored it: engine 1.0.0, no ownership in the response, and no
     * look-through snapshots. The table is append-only, so a copy is the only way to get one.
     */
    private String asIfMadeBeforeLookThrough(String id) {
        String copy = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO kyb_check (id, company_number, requested_at, completed_at, decision, score,
                    sanctions_dataset_version_id, scoring_config_version, engine_version, request, response)
                SELECT ?::uuid, company_number, requested_at, completed_at, decision, score,
                    sanctions_dataset_version_id, scoring_config_version, '1.0.0+normaliser.1', request,
                    replace(replace((response::jsonb - 'ownership')::text, ?, ?), '2.1.0+', '1.0.0+')::json
                  FROM kyb_check WHERE id = ?::uuid
                """,
                copy,
                id,
                copy,
                id);
        jdbc.update(
                """
                INSERT INTO kyb_check_source_snapshot (check_id, source, operation, fetched_at, from_cache, stale, payload)
                SELECT ?::uuid, source, operation, fetched_at, from_cache, stale, payload
                  FROM kyb_check_source_snapshot WHERE check_id = ?::uuid AND operation NOT LIKE 'ownership%'
                 ORDER BY id
                """,
                copy, id);
        return copy;
    }
}
