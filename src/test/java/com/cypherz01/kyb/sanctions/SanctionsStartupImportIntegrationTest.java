package com.cypherz01.kyb.sanctions;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.cypherz01.kyb.support.PostgresTestcontainer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A fresh install (no active dataset) with import-on-startup enabled loads the data by itself, so
 * a newcomer's first {@code docker compose up} ends with screenable data (spec §12 M7).
 *
 * <p>Uses a plain WireMockServer, stubbed before the context starts, because the import begins
 * while the context is still booting, before any per-test setup could run.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
class SanctionsStartupImportIntegrationTest {

    static final WireMockServer upstream = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        upstream.start();
        OpenSanctionsStubs.publish(
                new WireMock(upstream.port()), upstream.baseUrl(), "boot-v1", "sanctions/entities-v1.ftm.json");
    }

    @DynamicPropertySource
    static void sanctions(DynamicPropertyRegistry registry) {
        registry.add("kyb.sanctions.index-url", () -> upstream.baseUrl() + OpenSanctionsStubs.INDEX_PATH);
        registry.add("kyb.sanctions.import-on-startup-if-empty", () -> true);
        registry.add("kyb.sanctions.cron", () -> "-");
    }

    @AfterAll
    static void stop() {
        upstream.stop();
    }

    @Autowired
    SanctionsImportService service;

    @Test
    void importsOnStartupWhenNoDatasetIsActive() {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(service.status().active()).isNotNull());

        DatasetVersion active = service.status().active();
        assertThat(active.versionLabel()).isEqualTo("boot-v1");
        assertThat(active.entityCount()).isEqualTo(5);
    }
}
