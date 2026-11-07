package com.cypherz01.kyb.companieshouse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cypherz01.kyb.support.Fixtures;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

/**
 * Unit-level tests of the HTTP layer that need non-default limits (a tiny rate limit) and so are
 * cheaper without a Spring context. Behaviour through the full stack is in
 * {@link CompaniesHouseClientIntegrationTest}.
 */
class CompaniesHouseApiTest {

    static final String API_KEY = "unit-test-key";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    CompaniesHouseApi api;

    @BeforeEach
    void setUp() {
        CompaniesHouseProperties props = new CompaniesHouseProperties(
                URI.create(wm.baseUrl()),
                API_KEY,
                Duration.ofHours(24),
                100,
                50,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                // 2 calls per hour, and don't wait for a permit: the 3rd call must be refused locally
                new CompaniesHouseProperties.RateLimit(2, Duration.ofHours(1), Duration.ZERO),
                new CompaniesHouseProperties.Retry(3, Duration.ofMillis(10), Duration.ofMillis(100)));
        RestClient rest = CompaniesHouseApi.buildRestClient(RestClient.builder(), props);
        api = new CompaniesHouseApi(rest, props, Clock.systemUTC(), new SimpleMeterRegistry(), () -> 0.5);

        wm.stubFor(get(urlPathEqualTo("/company/12345678"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(Fixtures.read("companieshouse/profile.json"))));
    }

    @Test
    void sendsApiKeyAsBasicAuthUsernameWithEmptyPassword() {
        api.fetchProfile(CompanyNumber.parse("12345678"));

        String expected =
                "Basic " + Base64.getEncoder().encodeToString((API_KEY + ":").getBytes(StandardCharsets.UTF_8));
        wm.verify(getRequestedFor(urlPathEqualTo("/company/12345678")).withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void clientSideRateLimiterRefusesCallsBeyondTheLimitWithoutHittingUpstream() {
        CompanyNumber n = CompanyNumber.parse("12345678");
        api.fetchProfile(n);
        api.fetchProfile(n);

        assertThatThrownBy(() -> api.fetchProfile(n))
                .isInstanceOf(CompaniesHouseUnavailableException.class)
                .hasMessageContaining("rate limit");
        // The point of a client-side limiter: the refused call never reaches Companies House.
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo("/company/12345678"))))
                .hasSize(2);
    }

    @Test
    void propertiesToStringNeverRevealsTheApiKey() {
        CompaniesHouseProperties props = new CompaniesHouseProperties(
                URI.create("https://example.invalid"), "super-secret-key", null, 100, 50, null, null, null, null);

        assertThat(props.toString()).doesNotContain("super-secret-key").contains("****");
    }
}
