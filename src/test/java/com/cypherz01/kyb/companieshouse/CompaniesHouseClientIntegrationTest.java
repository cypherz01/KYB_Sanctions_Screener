package com.cypherz01.kyb.companieshouse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.MutableClock;
import com.cypherz01.kyb.support.MutableClockConfig;
import com.cypherz01.kyb.support.PostgresTestcontainer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M1 acceptance: the Companies House client against a WireMock'd upstream and a real Postgres L2
 * cache. Covers 200, 404, 429-then-success, pagination and cache hits (spec §12), plus the
 * retry/stale-fallback/logging behaviour from §5.1 and §10.
 */
@SpringBootTest
@Import({PostgresTestcontainer.class, MutableClockConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class CompaniesHouseClientIntegrationTest {

    static final String API_KEY = "it-secret-key-5f3a";
    static final String NUMBER = "12345678";
    static final String PROFILE = "/company/" + NUMBER;
    static final String OFFICERS = PROFILE + "/officers";
    static final String PSCS = PROFILE + "/persons-with-significant-control";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void companiesHouse(DynamicPropertyRegistry registry) {
        registry.add("kyb.companies-house.base-url", wm::baseUrl);
        registry.add("kyb.companies-house.api-key", () -> API_KEY);
        registry.add("kyb.companies-house.items-per-page", () -> 2);
        registry.add("kyb.companies-house.retry.base-delay", () -> "10ms");
        registry.add("kyb.companies-house.retry.max-delay", () -> "2s");
    }

    @Autowired
    CompaniesHouseClient client;

    @Autowired
    SourceCache cache;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        wm.resetAll();
        cache.clearAll();
        clock.set(MutableClockConfig.T0);
    }

    // ---------- 200 / mapping / provenance ----------

    @Test
    void fetchesAndMapsProfileAndReportsALiveCall() {
        stubProfile(json("profile.json"));

        Sourced<CompanyProfile> result = client.getProfile(NUMBER);

        assertThat(result.value().name()).isEqualTo("EXAMPLE TRADING LTD");
        assertThat(result.fromCache()).isFalse();
        assertThat(result.stale()).isFalse();
        assertThat(result.fetchedAt()).isEqualTo(MutableClockConfig.T0);
        // the raw upstream payload is retained for audit snapshots
        assertThat(result.payload().path("company_name").asText()).isEqualTo("EXAMPLE TRADING LTD");
    }

    @Test
    void canonicalisesTheCompanyNumberBeforeCallingUpstream() {
        wm.stubFor(get(urlPathEqualTo("/company/02345678")).willReturn(json("profile.json")));

        client.getProfile(" 2345678 ");

        wm.verify(getRequestedFor(urlPathEqualTo("/company/02345678")));
    }

    @Test
    void invalidCompanyNumberIsRejectedWithoutAnUpstreamCall() {
        assertThatThrownBy(() -> client.getProfile("not-a-number")).isInstanceOf(InvalidCompanyNumberException.class);
        assertThat(wm.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    // ---------- 404 ----------

    @Test
    void unknownCompanyIsNotFoundAndNotRetried() {
        wm.stubFor(get(urlPathEqualTo(PROFILE)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.getProfile(NUMBER)).isInstanceOf(CompanyNotFoundException.class);
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(PROFILE)))).hasSize(1);
    }

    @Test
    void notFoundOnOfficerOrPscListsMeansNoneRatherThanAnError() {
        wm.stubFor(get(urlPathEqualTo(OFFICERS)).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo(PSCS)).willReturn(aResponse().withStatus(404)));

        assertThat(client.getOfficers(NUMBER).value()).isEmpty();
        assertThat(client.getPscs(NUMBER).value()).isEmpty();
    }

    // ---------- 429 / 5xx / retry ----------

    @Test
    void retriesAfter429HonouringRetryAfterThenSucceeds() {
        wm.stubFor(get(urlPathEqualTo(PROFILE))
                .inScenario("rate-limited")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("recovered"));
        wm.stubFor(get(urlPathEqualTo(PROFILE))
                .inScenario("rate-limited")
                .whenScenarioStateIs("recovered")
                .willReturn(json("profile.json")));

        long start = System.nanoTime();
        Sourced<CompanyProfile> result = client.getProfile(NUMBER);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.value().name()).isEqualTo("EXAMPLE TRADING LTD");
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(PROFILE)))).hasSize(2);
        // waited for the server's Retry-After (1s), not the 10ms base backoff
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }

    @Test
    void givesUpAfterThreeRetriesOnPersistent429() {
        wm.stubFor(get(urlPathEqualTo(PROFILE)).willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> client.getProfile(NUMBER)).isInstanceOf(CompaniesHouseUnavailableException.class);
        // 1 initial attempt + 3 retries (spec §5.1: maximum 3 retries)
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(PROFILE)))).hasSize(4);
    }

    @Test
    void retriesTransientServerErrors() {
        wm.stubFor(get(urlPathEqualTo(PROFILE))
                .inScenario("flaky")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"));
        wm.stubFor(get(urlPathEqualTo(PROFILE))
                .inScenario("flaky")
                .whenScenarioStateIs("ok")
                .willReturn(json("profile.json")));

        assertThat(client.getProfile(NUMBER).value().name()).isEqualTo("EXAMPLE TRADING LTD");
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(PROFILE)))).hasSize(2);
    }

    @Test
    void rejectedCredentialsAreNotRetried() {
        wm.stubFor(get(urlPathEqualTo(PROFILE)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client.getProfile(NUMBER)).isInstanceOf(CompaniesHouseUnavailableException.class);
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(PROFILE)))).hasSize(1);
    }

    // ---------- pagination ----------

    @Test
    void followsOfficerPaginationAndMergesAllPages() {
        stubPage(OFFICERS, 0, "officers-page1.json");
        stubPage(OFFICERS, 2, "officers-page2.json");

        Sourced<List<Officer>> result = client.getOfficers(NUMBER);

        assertThat(result.value())
                .extracting(Officer::name)
                .containsExactly("PETROV, Ivan", "SMITH, Jane Elizabeth", "EXAMPLE NOMINEES LIMITED");
        wm.verify(getRequestedFor(urlPathEqualTo(OFFICERS)).withQueryParam("items_per_page", equalTo("2")));
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(OFFICERS)))).hasSize(2);
    }

    @Test
    void followsPscPaginationAndSendsTheParametersTheApiMarksRequired() {
        stubPage(PSCS, 0, "pscs-page1.json");
        stubPage(PSCS, 2, "pscs-page2.json");

        Sourced<List<Psc>> result = client.getPscs(NUMBER);

        assertThat(result.value()).hasSize(3);
        // The PSC endpoint documents items_per_page, start_index and register_view as required.
        wm.verify(
                2,
                getRequestedFor(urlPathEqualTo(PSCS))
                        .withQueryParam("items_per_page", equalTo("2"))
                        .withQueryParam("register_view", equalTo("false")));
    }

    @Test
    void stopsPaginatingIfUpstreamReturnsAnEmptyPage() {
        // total_results claims more than is ever delivered: must not loop forever
        wm.stubFor(get(urlPathEqualTo(OFFICERS))
                .withQueryParam("start_index", equalTo("0"))
                .willReturn(okJson("{\"total_results\": 50, \"items\": [{\"name\": \"A, B\"}]}")));
        wm.stubFor(get(urlPathEqualTo(OFFICERS))
                .withQueryParam("start_index", equalTo("1"))
                .willReturn(okJson("{\"total_results\": 50, \"items\": []}")));

        assertThat(client.getOfficers(NUMBER).value()).hasSize(1);
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(OFFICERS)))).hasSize(2);
    }

    // ---------- caching ----------

    @Test
    void secondCallIsACacheHitWithNoUpstreamRequest() {
        stubProfile(json("profile.json"));

        client.getProfile(NUMBER);
        clock.advance(Duration.ofHours(1));
        Sourced<CompanyProfile> second = client.getProfile(NUMBER);

        assertThat(second.fromCache()).isTrue();
        assertThat(second.stale()).isFalse();
        assertThat(second.fetchedAt()).isEqualTo(MutableClockConfig.T0); // when it was really fetched
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(PROFILE)))).hasSize(1);
    }

    @Test
    void durableCacheSurvivesLossOfTheInProcessCache() {
        stubProfile(json("profile.json"));
        client.getProfile(NUMBER);

        cache.clearLocal(); // simulates an app restart
        Sourced<CompanyProfile> afterRestart = client.getProfile(NUMBER);

        assertThat(afterRestart.fromCache()).isTrue();
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(PROFILE)))).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cache_entry WHERE source = 'companies-house'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void expiredEntryIsRefetched() {
        stubProfile(json("profile.json"));
        client.getProfile(NUMBER);

        clock.advance(Duration.ofHours(25)); // default TTL is 24h
        Sourced<CompanyProfile> refreshed = client.getProfile(NUMBER);

        assertThat(refreshed.fromCache()).isFalse();
        assertThat(refreshed.fetchedAt()).isEqualTo(MutableClockConfig.T0.plus(Duration.ofHours(25)));
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(PROFILE)))).hasSize(2);
    }

    @Test
    void outageServesExpiredCacheFlaggedStale() {
        stubProfile(json("profile.json"));
        client.getProfile(NUMBER);
        clock.advance(Duration.ofHours(25));
        wm.resetAll();
        wm.stubFor(get(urlPathEqualTo(PROFILE)).willReturn(aResponse().withStatus(503)));

        Sourced<CompanyProfile> result = client.getProfile(NUMBER);

        assertThat(result.value().name()).isEqualTo("EXAMPLE TRADING LTD");
        assertThat(result.fromCache()).isTrue();
        assertThat(result.stale()).isTrue();
        assertThat(result.fetchedAt()).isEqualTo(MutableClockConfig.T0);
    }

    @Test
    void outageWithNothingCachedFails() {
        wm.stubFor(get(urlPathEqualTo(PROFILE)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.getProfile(NUMBER)).isInstanceOf(CompaniesHouseUnavailableException.class);
    }

    @Test
    void notFoundIsNotMaskedByStaleCacheFallback() {
        // 404 is an answer, not an outage: it must not trigger the stale fallback path
        wm.stubFor(get(urlPathEqualTo(PROFILE)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.getProfile(NUMBER)).isInstanceOf(CompanyNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cache_entry", Integer.class))
                .isZero();
    }

    // ---------- observability ----------

    @Test
    void logsEachExternalCallWithSourceStatusAndLatencyButNeverTheKey(CapturedOutput output) {
        stubProfile(json("profile.json"));

        client.getProfile(NUMBER);

        assertThat(output.getOut())
                .contains("source=companies-house")
                .contains("status=200")
                .contains("latencyMs=")
                .doesNotContain(API_KEY);
    }

    @Test
    void recordsCallAndCacheMetrics() {
        stubProfile(json("profile.json"));

        client.getProfile(NUMBER);
        client.getProfile(NUMBER);

        assertThat(meters.get("kyb.external.calls")
                        .tag("source", "companies-house")
                        .tag("outcome", "200")
                        .timer()
                        .count())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.get("kyb.cache.requests")
                        .tag("source", "companies-house")
                        .tag("result", "hit")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1);
    }

    // ---------- helpers ----------

    private static ResponseDefinitionBuilder json(String fixture) {
        return okJson(Fixtures.read("companieshouse/" + fixture));
    }

    private static ResponseDefinitionBuilder okJson(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static void stubProfile(ResponseDefinitionBuilder response) {
        wm.stubFor(get(urlPathEqualTo(PROFILE)).willReturn(response));
    }

    private static void stubPage(String path, int startIndex, String fixture) {
        wm.stubFor(get(urlPathEqualTo(path))
                .withQueryParam("start_index", equalTo(Integer.toString(startIndex)))
                .willReturn(json(fixture)));
    }
}
