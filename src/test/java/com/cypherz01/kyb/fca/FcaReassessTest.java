package com.cypherz01.kyb.fca;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.fca.model.FcaAvailability;
import com.cypherz01.kyb.fca.model.FcaMatchBasis;
import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.InMemorySourceCache;
import com.cypherz01.kyb.support.MatchingDefaults;
import com.cypherz01.kyb.support.MutableClock;
import com.cypherz01.kyb.support.MutableClockConfig;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

/**
 * M6: an assessment can be repeated from what it stored. Each test takes a real assessment, removes
 * the Register, and requires an equal assessment with no call made, including evidence strings.
 */
class FcaReassessTest {

    static final String COMPANY = "ACME PAYMENTS LTD";
    static final String NUMBER = "12345678";
    static final String POSTCODE = "EC2A 1AB";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    MutableClock clock;
    FcaRegisterClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(MutableClockConfig.T0);
        FcaProperties props = FcaTestProperties.with(wm.baseUrl(), "kyb-test@example.com", "fca-key");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        client = new FcaRegisterClient(
                new FcaRegisterApi(FcaRegisterApi.buildRestClient(RestClient.builder(), props), props, meters),
                new InMemorySourceCache(),
                new FcaMapper(),
                MatchingDefaults.normaliser(),
                MatchingDefaults.matcher(),
                props,
                clock,
                meters);
    }

    @Test
    void aMatchOnCompaniesHouseNumber() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));

        FcaAssessment original = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(original.authorisedMatch().basis()).isEqualTo(FcaMatchBasis.COMPANIES_HOUSE_NUMBER);
        assertReassessedEqually(original);
    }

    @Test
    void cloneWarnings() {
        stubSearch("search-clone.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));

        FcaAssessment original = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(original.warnings()).isNotEmpty();
        assertReassessedEqually(original);
    }

    @Test
    void noHits() {
        stubSearch("search-empty.json");

        assertReassessedEqually(client.assess(COMPANY, NUMBER, POSTCODE));
    }

    @Test
    void firmDetailsThatWereUnavailableAtTheTimeStayUnavailable() {
        stubSearch("search-acme.json");
        wm.stubFor(get(urlPathEqualTo("/Firm/123456")).willReturn(aResponse().withStatus(503)));

        FcaAssessment original = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(original.authorisedMatch().basis()).isEqualTo(FcaMatchBasis.NAME_AND_POSTCODE);
        assertThat(original.evidence()).anyMatch(e -> e.contains("unavailable"));
        assertReassessedEqually(original);
    }

    @Test
    void aRegisterThatWasUnavailableAtTheTimeStaysUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(aResponse().withStatus(503)));

        FcaAssessment original = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(original.availability()).isEqualTo(FcaAvailability.UNAVAILABLE);
        assertThat(original.payload().path("unavailableReason").asText()).isEqualTo(original.unavailableReason());
        assertReassessedEqually(original);
    }

    @Test
    void aStaleResultStaysStaleWithItsOriginalFetchTime() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));
        client.assess(COMPANY, NUMBER, POSTCODE);
        clock.advance(Duration.ofDays(8));
        wm.resetAll();
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(aResponse().withStatus(503)));
        wm.stubFor(get(urlPathEqualTo("/Firm/123456")).willReturn(aResponse().withStatus(503)));

        FcaAssessment original = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(original.availability()).isEqualTo(FcaAvailability.STALE);
        assertThat(original.fetchedAt()).isEqualTo(MutableClockConfig.T0);
        assertReassessedEqually(original);
    }

    @Test
    void aCachedResultStaysCached() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));
        client.assess(COMPANY, NUMBER, POSTCODE);
        clock.advance(Duration.ofDays(1));

        FcaAssessment original = client.assess(COMPANY, NUMBER, POSTCODE);

        assertThat(original.fromCache()).isTrue();
        assertReassessedEqually(original);
    }

    @Test
    void reassessingDoesNotDependOnTheClockOrTheCache() {
        stubSearch("search-acme.json");
        stubFirm("123456", Fixtures.read("fca/firm-123456.json"));
        FcaAssessment original = client.assess(COMPANY, NUMBER, POSTCODE);
        clock.advance(Duration.ofDays(3650)); // the cached entries are long expired, and not consulted

        assertReassessedEqually(original);
    }

    private void assertReassessedEqually(FcaAssessment original) {
        wm.resetAll(); // no Register any more: any attempt to reach it would fail and be recorded

        FcaAssessment again = client.reassess(
                COMPANY,
                NUMBER,
                POSTCODE,
                new FcaSnapshot(original.payload(), original.fetchedAt(), original.fromCache(), original.stale()));

        assertThat(again).isEqualTo(original);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    private static void stubSearch(String fixture) {
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/" + fixture))));
    }

    private static void stubFirm(String frn, String body) {
        wm.stubFor(get(urlPathEqualTo("/Firm/" + frn)).willReturn(okJson(body)));
    }
}
