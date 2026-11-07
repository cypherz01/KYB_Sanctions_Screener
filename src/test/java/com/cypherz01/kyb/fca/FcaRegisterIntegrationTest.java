package com.cypherz01.kyb.fca;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.fca.model.FcaAvailability;
import com.cypherz01.kyb.fca.model.FcaMatchBasis;
import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.MutableClock;
import com.cypherz01.kyb.support.MutableClockConfig;
import com.cypherz01.kyb.support.PostgresTestcontainer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M4 acceptance through the Spring wiring and the Postgres-backed cache: success, outage with a
 * (durably) cached result served stale, and outage without one reported as unavailable.
 */
@SpringBootTest
@Import({PostgresTestcontainer.class, MutableClockConfig.class})
class FcaRegisterIntegrationTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void fca(DynamicPropertyRegistry registry) {
        registry.add("kyb.fca.base-url", wm::baseUrl);
        registry.add("kyb.fca.auth-email", () -> "it@example.com");
        registry.add("kyb.fca.auth-key", () -> "it-fca-key");
        registry.add("kyb.sanctions.cron", () -> "-");
    }

    @Autowired
    FcaRegisterClient client;

    @Autowired
    SourceCache cache;

    @Autowired
    MutableClock clock;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        wm.resetAll();
        cache.clearAll();
        clock.set(MutableClockConfig.T0);
    }

    @Test
    void assessesACompanyAgainstTheRegister() {
        stubHealthyRegister();

        FcaAssessment a = client.assess("ACME PAYMENTS LTD", "12345678", "EC2A 1AB");

        assertThat(a.availability()).isEqualTo(FcaAvailability.AVAILABLE);
        assertThat(a.authorisedMatch().basis()).isEqualTo(FcaMatchBasis.COMPANIES_HOUSE_NUMBER);
    }

    @Test
    void aRestartedProcessServesAStaleResultFromTheDurableCacheDuringAnOutage() {
        stubHealthyRegister();
        client.assess("ACME PAYMENTS LTD", "12345678", "EC2A 1AB");
        cache.clearLocal(); // as after a restart: only Postgres still has it
        clock.advance(Duration.ofDays(8));
        wm.resetAll();
        wm.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(503)));

        FcaAssessment a = client.assess("ACME PAYMENTS LTD", "12345678", "EC2A 1AB");

        assertThat(a.availability()).isEqualTo(FcaAvailability.STALE);
        assertThat(a.fetchedAt()).isEqualTo(MutableClockConfig.T0);
        assertThat(a.authorisedMatch().frn()).isEqualTo("123456");
    }

    @Test
    void anOutageWithNothingCachedIsUnavailable() {
        wm.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(500)));

        FcaAssessment a = client.assess("NEVER SEEN LTD", null, null);

        assertThat(a.availability()).isEqualTo(FcaAvailability.UNAVAILABLE);
        assertThat(wm.findAll(anyRequestedFor(anyUrl()))).isNotEmpty();
    }

    @Test
    void exposesCircuitBreakerAndRateLimiterMetrics() {
        assertThat(meters.find("resilience4j.circuitbreaker.state")
                        .tag("name", "fca-register")
                        .gauges())
                .isNotEmpty();
        assertThat(meters.find("resilience4j.ratelimiter.available.permissions")
                        .tag("name", "fca-register")
                        .gauge())
                .isNotNull();
    }

    private static void stubHealthyRegister() {
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/search-acme.json"))));
        wm.stubFor(get(urlPathEqualTo("/Firm/123456")).willReturn(okJson(Fixtures.read("fca/firm-123456.json"))));
    }
}
