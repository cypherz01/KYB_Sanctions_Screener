package com.cypherz01.kyb.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.monitoring.MonitoringWorker;
import com.cypherz01.kyb.sanctions.ImportOutcome;
import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.support.ApiIntegrationTest;
import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** ADR-0006: with no endpoint configured, alerts are still recorded and listed. */
@ApiIntegrationTest
class MonitoringWithoutWebhookIntegrationTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("kyb.companies-house.base-url", wm::baseUrl);
        registry.add("kyb.companies-house.api-key", () -> "it-ch-key");
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
    MonitoringWorker worker;

    @Test
    void anAlertIsRecordedAndListedAndNothingIsQueued() throws Exception {
        cache.clearAll();
        jdbc.update("TRUNCATE kyb_check CASCADE");
        jdbc.update("DELETE FROM sanctions_dataset_version");
        publish("no-hook-1", Fixtures.read("sanctions/entities-screening.ftm.json"));
        stub();
        worker.tick();
        assertThat(http.postForEntity(
                                "/api/v1/monitoring/subscriptions", Map.of("companyNumber", "07654321"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        publish(
                "no-hook-2",
                Fixtures.read("sanctions/entities-screening.ftm.json")
                        + "{\"id\":\"NK-sarah\",\"caption\":\"Sarah Jones\",\"schema\":\"Person\",\"datasets\":[\"eu_fsf\"],"
                        + "\"properties\":{\"name\":[\"Sarah Jones\"],\"birthDate\":[\"1980-05-01\"],\"topics\":[\"sanction\"]},\"target\":true}\n");
        worker.tick();

        JsonNode alerts = json.readTree(http.getForObject("/api/v1/monitoring/alerts", String.class));
        assertThat(alerts).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_outbox", Long.class))
                .isZero();
    }

    private void publish(String version, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OpenSanctionsStubs.publish(
                wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), version, bytes, OpenSanctionsStubs.sha1(bytes));
        assertThat(imports.importLatest().outcome()).isEqualTo(ImportOutcome.COMPLETED);
    }

    private static void stub() {
        String base = "/company/07654321";
        wm.stubFor(get(urlPathEqualTo(base)).willReturn(okJson(Fixtures.read("checks/clean-profile.json"))));
        wm.stubFor(get(urlPathEqualTo(base + "/officers"))
                .willReturn(okJson(Fixtures.read("checks/clean-officers.json"))));
        wm.stubFor(get(urlPathEqualTo(base + "/persons-with-significant-control"))
                .willReturn(okJson(Fixtures.read("checks/clean-pscs.json"))));
        wm.stubFor(
                get(urlPathEqualTo(base + "/exemptions")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/search-empty.json"))));
    }
}
