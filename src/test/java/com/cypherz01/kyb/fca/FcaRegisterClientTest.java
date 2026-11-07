package com.cypherz01.kyb.fca;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.fca.model.FcaAvailability;
import com.cypherz01.kyb.fca.model.FcaFirmMatch;
import com.cypherz01.kyb.fca.model.FcaMatchBasis;
import com.cypherz01.kyb.fca.model.FcaWarningMatch;
import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.InMemorySourceCache;
import com.cypherz01.kyb.support.MatchingDefaults;
import com.cypherz01.kyb.support.MutableClock;
import com.cypherz01.kyb.support.MutableClockConfig;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

/**
 * M4 behaviour against a WireMock'd Register (no Spring context): matching a company to a firm,
 * clone/unauthorised warnings, caching, stale fallback, unavailable without cache, circuit breaker.
 */
@ExtendWith(OutputCaptureExtension.class)
class FcaRegisterClientTest {

    static final String EMAIL = "kyb-test@example.com";
    static final String KEY = "fca-secret-key-7d1e";
    static final String COMPANY = "ACME PAYMENTS LTD";
    static final String NUMBER = "12345678";
    static final String POSTCODE = "EC2A 1AB";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    MutableClock clock;
    InMemorySourceCache cache;
    MeterRegistry meters;
    FcaRegisterClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(MutableClockConfig.T0);
        cache = new InMemorySourceCache();
        meters = new SimpleMeterRegistry();
        client = clientWith(FcaTestProperties.with(wm.baseUrl(), EMAIL, KEY));
    }

    private FcaRegisterClient clientWith(FcaProperties props) {
        FcaRegisterApi api =
                new FcaRegisterApi(FcaRegisterApi.buildRestClient(RestClient.builder(), props), props, meters);
        return new FcaRegisterClient(
                api,
                cache,
                new FcaMapper(),
                MatchingDefaults.normaliser(),
                MatchingDefaults.matcher(),
                props,
                clock,
                meters);
    }

    // ---------- matching a company to the Register ----------

    @Test
    void anAuthorisedFirmWithTheSameCompaniesHouseNumberIsACertainMatch() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));

        FcaAssessment a = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(a.availability()).isEqualTo(FcaAvailability.AVAILABLE);
        FcaFirmMatch m = a.authorisedMatch();
        assertThat(m.frn()).isEqualTo("123456");
        assertThat(m.name()).isEqualTo("Acme Payments Ltd");
        assertThat(m.status()).isEqualTo("Authorised");
        assertThat(m.basis()).isEqualTo(FcaMatchBasis.COMPANIES_HOUSE_NUMBER);
        assertThat(m.confidence()).isEqualTo(1.0);
        assertThat(m.companiesHouseNumber()).isEqualTo(NUMBER);
        assertThat(a.warnings()).isEmpty();
        assertThat(a.fromCache()).isFalse();
        assertThat(a.stale()).isFalse();
        assertThat(a.fetchedAt()).isEqualTo(MutableClockConfig.T0);
        assertThat(a.query()).isEqualTo(COMPANY);
    }

    @Test
    void sendsTheNameAsAFirmSearchWithTheAuthHeaders() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));

        client.assess(COMPANY, NUMBER, POSTCODE);

        wm.verify(getRequestedFor(urlPathEqualTo("/Search"))
                .withQueryParam("q", equalTo(COMPANY))
                .withQueryParam("type", equalTo("firm"))
                .withHeader("X-AUTH-EMAIL", equalTo(EMAIL))
                .withHeader("X-AUTH-KEY", equalTo(KEY))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    void withoutACompaniesHouseNumberTheMatchRestsOnNameAndPostcode() {
        stubSearch("search-acme.json");
        stubFirm("123456", firmJson("123456", "Acme Payments Ltd", "Authorised", ""));

        FcaFirmMatch m = client.assess(COMPANY, NUMBER, "ec2a1ab").authorisedMatch();

        assertThat(m.frn()).isEqualTo("123456");
        assertThat(m.basis()).isEqualTo(FcaMatchBasis.NAME_AND_POSTCODE);
        assertThat(m.confidence()).isBetween(0.8, 1.0);
    }

    @Test
    void withoutAPostcodeTheMatchRestsOnTheNameAlone() {
        stubSearch("search-acme.json");
        stubFirm("123456", firmJson("123456", "Acme Payments Ltd", "Authorised", ""));

        FcaFirmMatch m = client.assess(COMPANY, null, null).authorisedMatch();

        assertThat(m.basis()).isEqualTo(FcaMatchBasis.NAME);
    }

    @Test
    void aDifferentCompaniesHouseNumberRulesTheFirmOut() {
        stubSearch("search-acme.json");
        stubFirm("123456", firmJson("123456", "Acme Payments Ltd", "Authorised", "87654321"));

        FcaAssessment a = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(a.authorisedMatch()).isNull();
        assertThat(a.evidence()).anyMatch(e -> e.contains("87654321"));
    }

    @Test
    void aFormerAuthorisationIsNotAnAuthorisationButIsExplained() {
        wm.stubFor(
                get(urlPathEqualTo("/Search"))
                        .willReturn(
                                okJson(
                                        """
                        {"Status":"FSR-API-04-01-00","Message":"Ok. Search successful","Data":[
                          {"Status":"No longer authorised","Reference Number":"222222",
                           "Type of business or Individual":"Firm",
                           "Name":"Acme Payments Holdings Limited (Postcode: M1 1AA)"}]}
                        """)));

        FcaAssessment a = client.assess("Acme Payments Holdings Limited", null, null);

        assertThat(a.authorisedMatch()).isNull();
        assertThat(a.evidence()).anyMatch(e -> e.contains("No longer authorised"));
    }

    @Test
    void noSearchResultsMeansNoAuthorisationFound() {
        stubSearch("search-empty.json");

        FcaAssessment a = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(a.availability()).isEqualTo(FcaAvailability.AVAILABLE);
        assertThat(a.authorisedMatch()).isNull();
        assertThat(a.warnings()).isEmpty();
    }

    @Test
    void firmsWithDifferentNamesAreIgnored() {
        stubSearch("search-acme.json");

        FcaAssessment a = client.assess("Totally Different Widgets Ltd", null, null);

        assertThat(a.authorisedMatch()).isNull();
        wm.verify(0, getRequestedFor(urlPathMatching("/Firm/.*"))); // no details for non-matching names
    }

    @Test
    void firmDetailsAreFetchedOnlyForTheStrongestFewHits() {
        stubSearch("search-many.json");
        wm.stubFor(get(urlPathMatching("/Firm/.*"))
                .willReturn(okJson(firmJson("500001", "Acme Payments Ltd", "Authorised", ""))));

        client.assess(COMPANY, NUMBER, POSTCODE);

        wm.verify(3, getRequestedFor(urlPathMatching("/Firm/.*"))); // max-details-lookups = 3
    }

    @Test
    void aFirmDetailsOutageFallsBackToTheNameMatch() {
        stubSearch("search-acme.json");
        wm.stubFor(get(urlPathEqualTo("/Firm/123456")).willReturn(aResponse().withStatus(503)));

        FcaAssessment a = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(a.availability()).isEqualTo(FcaAvailability.AVAILABLE);
        assertThat(a.authorisedMatch().basis()).isEqualTo(FcaMatchBasis.NAME_AND_POSTCODE);
        assertThat(a.evidence()).anyMatch(e -> e.toLowerCase().contains("details"));
    }

    @Test
    void keepsTheRawResponsesForAudit() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));

        FcaAssessment a = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(a.payload().path("search").path("Data").size()).isEqualTo(3);
        assertThat(a.payload()
                        .path("firms")
                        .path("123456")
                        .path("Data")
                        .get(0)
                        .path("FRN")
                        .asText())
                .isEqualTo("123456");
    }

    // ---------- clone and unauthorised firms ----------

    @Test
    void cloneAndUnauthorisedEntriesWithAMatchingNameAreWarnings() {
        stubSearch("search-clone.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));

        FcaAssessment a = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(a.warnings()).extracting(FcaWarningMatch::frn).containsExactlyInAnyOrder("999001", "999002");
        FcaWarningMatch byStatus = a.warnings().stream()
                .filter(w -> w.frn().equals("999001"))
                .findFirst()
                .orElseThrow();
        assertThat(byStatus.similarity()).isGreaterThanOrEqualTo(0.8);
        assertThat(byStatus.evidence()).anyMatch(e -> e.contains("Unauthorised"));
        FcaWarningMatch byName = a.warnings().stream()
                .filter(w -> w.frn().equals("999002"))
                .findFirst()
                .orElseThrow();
        assertThat(byName.evidence()).anyMatch(e -> e.toLowerCase().contains("clone"));
        // the genuine firm is still recognised alongside its clones
        assertThat(a.authorisedMatch().frn()).isEqualTo("123456");
    }

    @Test
    void unauthorisedEntriesForOtherNamesAreNotWarnings() {
        stubSearch("search-clone.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));

        assertThat(client.assess(COMPANY, NUMBER, POSTCODE).warnings())
                .extracting(FcaWarningMatch::frn)
                .doesNotContain("999003");
    }

    // ---------- caching ----------

    @Test
    void aRepeatAssessmentIsServedFromCache() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));
        client.assess(COMPANY, NUMBER, POSTCODE);
        int calls = wm.findAll(anyRequestedFor(anyUrl())).size();

        FcaAssessment again = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(wm.findAll(anyRequestedFor(anyUrl()))).hasSize(calls);
        assertThat(again.fromCache()).isTrue();
        assertThat(again.authorisedMatch().basis()).isEqualTo(FcaMatchBasis.COMPANIES_HOUSE_NUMBER);
    }

    @Test
    void expiredEntriesAreRefetched() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));
        client.assess(COMPANY, NUMBER, POSTCODE);
        clock.advance(Duration.ofDays(8)); // TTL 7 days

        FcaAssessment later = client.assess(COMPANY, NUMBER, POSTCODE);

        wm.verify(2, getRequestedFor(urlPathEqualTo("/Search")));
        assertThat(later.fromCache()).isFalse();
        assertThat(later.fetchedAt()).isEqualTo(MutableClockConfig.T0.plus(Duration.ofDays(8)));
    }

    // ---------- outages (spec §5.2, M4 acceptance) ----------

    @Test
    void anOutageWithACachedResultServesItAsStale() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));
        client.assess(COMPANY, NUMBER, POSTCODE);
        clock.advance(Duration.ofDays(8));
        wm.resetAll();
        wm.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(503)));

        FcaAssessment a = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(a.availability()).isEqualTo(FcaAvailability.STALE);
        assertThat(a.stale()).isTrue();
        assertThat(a.fromCache()).isTrue();
        assertThat(a.fetchedAt()).isEqualTo(MutableClockConfig.T0);
        assertThat(a.authorisedMatch().frn()).isEqualTo("123456");
    }

    @Test
    void anOutageWithoutACachedResultIsUnavailableNotAnError() {
        wm.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(503)));

        FcaAssessment a = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(a.availability()).isEqualTo(FcaAvailability.UNAVAILABLE);
        assertThat(a.unavailableReason()).contains("503");
        assertThat(a.authorisedMatch()).isNull();
        assertThat(a.fetchedAt()).isNull();
    }

    @Test
    void rateLimitingIsAnOutageAndIsNotRetried() {
        wm.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(429)));

        FcaAssessment a = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(a.availability()).isEqualTo(FcaAvailability.UNAVAILABLE);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/Search")));
    }

    @Test
    void anUnreachableRegisterIsUnavailable() {
        FcaRegisterClient dead = clientWith(FcaTestProperties.with("http://127.0.0.1:1", EMAIL, KEY));

        assertThat(dead.assess(COMPANY, NUMBER, POSTCODE).availability()).isEqualTo(FcaAvailability.UNAVAILABLE);
    }

    @Test
    void theCircuitBreakerStopsCallingADeadRegister() {
        wm.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 4; i++) { // window 4, minimum 3 calls, 50% failures
            client.assess("Company " + i, null, null);
        }
        int before = wm.findAll(anyRequestedFor(anyUrl())).size();

        FcaAssessment a = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(wm.findAll(anyRequestedFor(anyUrl()))).hasSize(before);
        assertThat(a.availability()).isEqualTo(FcaAvailability.UNAVAILABLE);
        assertThat(a.unavailableReason()).containsIgnoringCase("circuit");
    }

    @Test
    void withoutCredentialsNothingIsCalledAndTheResultIsUnavailable() {
        FcaRegisterClient unconfigured = clientWith(FcaTestProperties.with(wm.baseUrl(), "", ""));

        FcaAssessment a = unconfigured.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(a.availability()).isEqualTo(FcaAvailability.UNAVAILABLE);
        assertThat(a.unavailableReason()).containsIgnoringCase("credentials");
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    // ---------- operability ----------

    @Test
    void logsEachCallWithoutTheCredentials(CapturedOutput output) {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));

        client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(output).contains("external call source=fca-register operation=search status=200");
        assertThat(output).contains("external call source=fca-register operation=firm status=200");
        assertThat(output).doesNotContain(KEY).doesNotContain(EMAIL);
    }

    @Test
    void recordsCallTimingsAndCacheResults() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));

        client.assess(COMPANY, NUMBER, POSTCODE);
        client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(meters.find("kyb.external.calls")
                        .tags("source", "fca-register", "operation", "search", "outcome", "200")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(meters.find("kyb.cache.requests")
                        .tags("source", "fca-register", "result", "hit")
                        .counter()
                        .count())
                .isPositive();
    }

    // ---------- helpers ----------

    private static void stubSearch(String fixture) {
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/" + fixture))));
    }

    private static void stubFirm(String frn, String body) {
        wm.stubFor(get(urlPathEqualTo("/Firm/" + frn)).willReturn(okJson(body)));
    }

    static String firmJson(String frn, String name, String status, String chNumber) {
        return """
                {"Status":"FSR-API-02-01-00","Message":"Ok. Firm Found","Data":[
                  {"Organisation Name":"%s","Status":"%s","Business Type":"Regulated","FRN":"%s",
                   "Companies House Number":"%s"}]}
                """
                .formatted(name, status, frn, chNumber);
    }
}
