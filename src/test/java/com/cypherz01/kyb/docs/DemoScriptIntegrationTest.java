package com.cypherz01.kyb.docs;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cypherz01.kyb.sanctions.ImportOutcome;
import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.support.ApiIntegrationTest;
import com.cypherz01.kyb.support.ApiKeyTestConfig;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M7: the demo a newcomer runs really works. The real application, with the seed data and stubs the
 * demo ships (the same directory docker-compose.demo.yml mounts), driven by the real script. Only
 * Docker itself is left out, and CI runs that path too.
 */
@ApiIntegrationTest
class DemoScriptIntegrationTest {

    @RegisterExtension
    static WireMockExtension stubs = WireMockExtension.newInstance()
            .options(wireMockConfig()
                    .dynamicPort()
                    .usingFilesUnderDirectory("demo/wiremock")
                    .globalTemplating(true))
            .build();

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("kyb.companies-house.base-url", stubs::baseUrl);
        registry.add("kyb.companies-house.api-key", () -> "demo");
        registry.add("kyb.fca.base-url", stubs::baseUrl);
        registry.add("kyb.fca.auth-email", () -> "demo@example.com");
        registry.add("kyb.fca.auth-key", () -> "demo");
        registry.add("kyb.sanctions.index-url", () -> stubs.baseUrl() + OpenSanctionsStubs.INDEX_PATH);
    }

    /** One run per class: the tests are all questions about the same output. */
    private static Result demo;

    @LocalServerPort
    int port;

    @Autowired
    SanctionsImportService imports;

    @BeforeEach
    void runDemoOnce() throws Exception {
        // Skipping is for a developer's machine without bash. CI must really run this.
        if (System.getenv("CI") == null) {
            assumeTrue(works(bash()), "needs bash, as the demo script does");
        }
        if (demo == null) {
            assertThat(imports.importLatest().outcome()).isEqualTo(ImportOutcome.COMPLETED);
            demo = run();
        }
    }

    @Test
    void finishesSuccessfully() {
        assertThat(demo.exitCode()).as(demo.output()).isZero();
    }

    @Test
    void showsACleanCompanyPassing() {
        assertThat(demo.output()).contains("BRIGHT BAKERY LTD").contains("PASS");
    }

    @Test
    void showsACloneWarningSendingACompanyToReview() {
        assertThat(demo.output())
                .contains("ACME PAYMENTS LTD")
                .contains("REVIEW")
                .contains("FCA_CLONE_OR_UNAUTHORISED_MATCH");
    }

    @Test
    void showsASanctionedDirectorFailingACompany() {
        assertThat(demo.output()).contains("ACME WIDGETS LTD").contains("FAIL").contains("SANCTIONS_STRONG_MATCH");
    }

    /** M8: the owner is two companies away, and the arithmetic behind the decision is on show. */
    @Test
    void showsASanctionedOwnerTwoLevelsUpFailingACompany() {
        assertThat(demo.output())
                .contains("NORTHGATE FOODS LTD")
                .contains("two levels up")
                .contains("56.25");
    }

    /** The point of the service is that it explains itself, so the demo must show the reasoning. */
    @Test
    void showsWhyTheMatchWasMade() {
        assertThat(demo.output()).contains("Nationality matches (RU)");
    }

    @Test
    void showsATransliteratedNameMatching() {
        assertThat(demo.output()).contains("Иван Петров").contains("STRONG");
    }

    @Test
    void showsAReplayReproducingTheDecision() {
        assertThat(demo.output()).contains("identical: true");
    }

    @Test
    void showsTheAuditTrail() {
        assertThat(demo.output()).contains("CHECK_REQUESTED").contains("CHECK_REPLAYED");
    }

    /** Fake companies and a fake list, which must never be mistaken for a finding about anyone real. */
    @Test
    void saysTheDataIsSynthetic() {
        assertThat(demo.output()).containsIgnoringCase("synthetic");
    }

    @Test
    void theSeedDatasetMatchesTheChecksumItsIndexAdvertises() throws IOException {
        byte[] seed = Files.readAllBytes(Path.of("demo/wiremock/__files/entities.ftm.json"));
        String mappings = Files.readString(Path.of("demo/wiremock/mappings/opensanctions.json"));

        assertThat(mappings).contains(OpenSanctionsStubs.sha1(seed));
    }

    private Result run() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(bash(), "scripts/demo.sh", "--no-start").redirectErrorStream(true);
        builder.environment().put("KYB_DEMO_URL", "http://localhost:" + port);
        builder.environment().put("KYB_DEMO_API_KEY", ApiKeyTestConfig.KEY);
        Process process = builder.start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("demo finished").isTrue();
        return new Result(process.exitValue(), output);
    }

    /**
     * On Windows, "bash" on the PATH is usually the WSL launcher, which fails without a Linux distro
     * installed and would silently skip these tests, so Git Bash is preferred where it exists.
     */
    private static String bash() {
        Path gitBash = Path.of("C:\\Program Files\\Git\\bin\\bash.exe");
        return Files.exists(gitBash) ? gitBash.toString() : "bash";
    }

    private static boolean works(String bash) {
        try {
            Process p = new ProcessBuilder(bash, "--version").start();
            p.getOutputStream().close();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record Result(int exitCode, String output) {}
}
