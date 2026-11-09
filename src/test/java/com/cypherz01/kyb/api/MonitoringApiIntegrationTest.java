package com.cypherz01.kyb.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.monitoring.MonitoringWorker;
import com.cypherz01.kyb.monitoring.WebhookDispatcher;
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
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M9 acceptance, end to end: the real application, with WireMock as Companies House, OpenSanctions and
 * the webhook receiver, and the workers driven directly. Everything here is invented.
 */
@ApiIntegrationTest
@Import(MutableClockConfig.class)
class MonitoringApiIntegrationTest {

    static final String SUBSCRIPTIONS = "/api/v1/monitoring/subscriptions";
    static final String ALERTS = "/api/v1/monitoring/alerts";
    static final String CHECKS = "/api/v1/checks";
    static final String FIXTURE = "sanctions/entities-screening.ftm.json";
    static final String WEBHOOK = "/hooks/kyb";
    static final String SECRET = "it-webhook-secret";

    static final String CLEAN = "07654321";
    static final String SANCTIONED = "01234567";
    static final String HOLDCO = "20000001";

    /** The director of the clean company, as the fixture list would need to name her to match. */
    static final String LISTS_SARAH = person("NK-sarah", "Sarah Jones", "1980-05-01");

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
        registry.add("kyb.monitoring.jobs-per-tick", () -> "2");
        registry.add("kyb.monitoring.webhook.url", () -> wm.baseUrl() + WEBHOOK);
        registry.add("kyb.monitoring.webhook.secret", () -> SECRET);
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

    @Autowired
    WebhookDispatcher webhooks;

    @Autowired
    MutableClock clock;

    @Autowired
    PlatformTransactionManager transactions;

    int datasets;

    @BeforeEach
    void reset() {
        clock.set(MutableClockConfig.T0);
        wm.resetAll();
        cache.clearAll();
        jdbc.update("TRUNCATE kyb_check CASCADE");
        jdbc.update("DELETE FROM sanctions_dataset_version");
        jdbc.update("UPDATE monitoring_state SET last_dataset_version_id = NULL, fingerprint = NULL");
        importDataset("");
        stubCompany(CLEAN, "clean");
        stubCompany(SANCTIONED, "sanctioned");
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/search-empty.json"))));
        wm.stubFor(post(urlPathEqualTo(WEBHOOK)).willReturn(aResponse().withStatus(204)));
        worker.tick(); // settles the first dataset, so each test starts from a screened baseline
    }

    // ---------- opting in and out ----------

    @Test
    void aCompanyIsNotWatchedUntilSomeoneSubscribes() throws Exception {
        runCheck(CLEAN);
        importDataset(LISTS_SARAH);

        worker.tick();

        assertThat(checks()).isEqualTo(1);
        assertThat(alerts()).isEmpty();
    }

    @Test
    void subscribingToACompanyWithNoCheckRunsOne() throws Exception {
        ResponseEntity<String> response = subscribeResponse(CLEAN);

        JsonNode body = json.readTree(response.getBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(checks()).isEqualTo(1);
        assertThat(body.path("subscriptionId").asText()).isNotBlank();
        assertThat(body.path("companyNumber").asText()).isEqualTo(CLEAN);
        assertThat(body.path("latestCheckId").asText()).isNotBlank();
    }

    @Test
    void subscribingToACheckedCompanyWatchesItsLatestCheck() throws Exception {
        String checkId = runCheck(CLEAN).path("checkId").asText();

        JsonNode body = subscribe(CLEAN);

        assertThat(checks()).isEqualTo(1);
        assertThat(body.path("latestCheckId").asText()).isEqualTo(checkId);
    }

    @Test
    void subscribingTwiceChangesNothing() throws Exception {
        JsonNode first = subscribe(CLEAN);

        ResponseEntity<String> again = subscribeResponse(CLEAN);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(again.getBody()).path("subscriptionId")).isEqualTo(first.path("subscriptionId"));
        assertThat(checks()).isEqualTo(1);
    }

    @Test
    void subscribingToAnUnknownOrMalformedCompanyIsRefused() {
        assertThat(subscribeResponse("99999999").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(subscribeResponse("not a number").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** The data-minimisation point of ADR-0004. */
    @Test
    void unsubscribingStopsTheWatchAndDeletesTheNamesButKeepsTheRecord() throws Exception {
        subscribe(CLEAN);
        importDataset(LISTS_SARAH);
        worker.tick();
        assertThat(alerts()).hasSize(1);
        long checks = checks();

        ResponseEntity<Void> stopped = unsubscribe(CLEAN);

        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("monitoring_subject")).isZero();
        assertThat(count("monitoring_match")).isZero();
        assertThat(alerts()).hasSize(1);

        importDataset(LISTS_SARAH + person("NK-other", "Sarah Jones", "1980-05-02"));
        worker.tick();
        assertThat(checks()).isEqualTo(checks);
    }

    @Test
    void unsubscribingFromSomethingNotWatchedIsNotFound() {
        assertThat(unsubscribe(CLEAN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCompanyCanBeWatchedAgainAfterStopping() throws Exception {
        subscribe(CLEAN);
        unsubscribe(CLEAN);

        assertThat(subscribeResponse(CLEAN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---------- a sanctions import ----------

    @Test
    void aNewlyListedDirectorRaisesAnAlertLinkedToANormalCheck() throws Exception {
        JsonNode subscription = subscribe(CLEAN);
        String previous = subscription.path("latestCheckId").asText();

        importDataset(LISTS_SARAH);
        worker.tick();

        List<JsonNode> alerts = alerts();
        assertThat(alerts).hasSize(1);
        JsonNode alert = alerts.get(0);
        assertThat(alert.path("type").asText()).isEqualTo("MATCHES_CHANGED");
        assertThat(alert.path("trigger").asText()).isEqualTo("SANCTIONS_UPDATE");
        assertThat(alert.path("companyNumber").asText()).isEqualTo(CLEAN);
        assertThat(alert.path("subscriptionId")).isEqualTo(subscription.path("subscriptionId"));
        assertThat(alert.path("decisionBefore").asText()).isEqualTo("PASS");
        assertThat(alert.path("decisionAfter").asText()).isEqualTo("FAIL");
        assertThat(alert.path("previousCheckId").asText()).isEqualTo(previous);
        assertThat(alert.path("matchesAdded").toString()).contains("NK-sarah");

        // It is an ordinary check, with the ordinary audit trail and replay.
        String checkId = alert.path("checkId").asText();
        JsonNode trail = json.readTree(http.getForObject(CHECKS + "/" + checkId + "/audit", String.class));
        assertThat(trail.path("request").path("trigger").asText()).isEqualTo("SANCTIONS_UPDATE");
        assertThat(trail.path("request").path("previousCheckId").asText()).isEqualTo(previous);
        assertThat(trail.path("request").path("subscriptionId")).isEqualTo(subscription.path("subscriptionId"));
        JsonNode replay = json.readTree(http.postForObject(CHECKS + "/" + checkId + "/replay", null, String.class));
        assertThat(replay.path("identical").asBoolean()).as(replay.toString()).isTrue();
    }

    @Test
    void anImportThatTouchesNobodyRunsNoCheck() throws Exception {
        subscribe(CLEAN);

        importDataset(person("NK-zebulon", "Zebulon Quixote", "1950-01-01"));
        worker.tick();

        assertThat(checks()).isEqualTo(1);
        assertThat(alerts()).isEmpty();
    }

    /** A listed entity changed, so it is checked again, but the outcome did not move. */
    @Test
    void aHitThatChangesNoOutcomeRaisesNoAlert() throws Exception {
        subscribe(SANCTIONED);

        publish(Fixtures.read(FIXTURE)
                .replace(
                        "\"topics\":[\"sanction\"],\"programId\":[\"GB-RUS\"]",
                        "\"topics\":[\"sanction\",\"sanction.linked\"],\"programId\":[\"GB-RUS\"]"));
        worker.tick();

        assertThat(checks()).isEqualTo(2);
        assertThat(alerts()).isEmpty();
    }

    @Test
    void aRemovedEntityRaisesMatchCleared() throws Exception {
        subscribe(SANCTIONED);

        importWithout("NK-ivan");
        worker.tick();

        List<JsonNode> alerts = alerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).path("type").asText()).isEqualTo("MATCH_CLEARED");
        assertThat(alerts.get(0).path("decisionBefore").asText()).isEqualTo("FAIL");
        assertThat(alerts.get(0).path("matchesRemoved").toString()).contains("NK-ivan");
    }

    @Test
    void anOwnerFoundByLookThroughIsWatched() throws Exception {
        stubParentOwnedBy(HOLDCO, "Mr Karl Olsen", 1960, 6);
        wm.stubFor(get(urlPathEqualTo("/company/" + CLEAN + "/persons-with-significant-control"))
                .willReturn(okJson(
                        """
                        {"items":[{"name":"HARBOUR HOLDINGS LTD","kind":"corporate-entity-person-with-significant-control",
                          "notified_on":"2016-06-30","natures_of_control":["ownership-of-shares-75-to-100-percent"],
                          "identification":{"country_registered":"England","registration_number":"%s"}}],"total_results":1}
                        """
                                .formatted(HOLDCO))));
        subscribe(CLEAN);

        importDataset(person("NK-olsen", "Karl Olsen", "1960-06-01"));
        worker.tick();

        assertThat(alerts()).hasSize(1);
        assertThat(alerts().get(0).path("decisionAfter").asText()).isEqualTo("FAIL");
    }

    @Test
    void aNewCheckRefreshesWhoIsWatched() throws Exception {
        subscribe(CLEAN);
        wm.stubFor(
                get(urlPathEqualTo("/company/" + CLEAN + "/officers"))
                        .willReturn(
                                okJson(
                                        """
                        {"items":[{"name":"OLSEN, Karl","officer_role":"director","appointed_on":"2010-05-01",
                          "date_of_birth":{"month":6,"year":1960}}],"total_results":1}
                        """)));
        cache.clearAll();
        runCheck(CLEAN);

        importDataset(person("NK-olsen", "Karl Olsen", "1960-06-01"));
        worker.tick();

        assertThat(alerts()).hasSize(1);
        assertThat(alerts().get(0).path("matchesAdded").toString()).contains("NK-olsen");
    }

    /** Otherwise the manual check moves the baseline, and monitoring never reports the change. */
    @Test
    void aManualCheckThatFindsAChangeRaisesTheAlertAndMonitoringDoesNotRepeatIt() throws Exception {
        subscribe(CLEAN);
        importDataset(LISTS_SARAH);

        runCheck(CLEAN);

        assertThat(alerts()).hasSize(1);
        assertThat(alerts().get(0).path("trigger").asText()).isEqualTo("MANUAL");

        worker.tick();
        assertThat(alerts()).hasSize(1);
    }

    @Test
    void theAlertListCanBeNarrowedToOneCompany() throws Exception {
        subscribe(CLEAN);
        subscribe(SANCTIONED);
        importDataset(LISTS_SARAH);
        worker.tick();

        JsonNode list = json.readTree(http.getForObject(ALERTS + "?companyNumber=" + CLEAN, String.class));

        // Sarah Jones is an officer of both, so both raise an alert, and only one is asked for.
        assertThat(alerts()).hasSize(2);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("companyNumber").asText()).isEqualTo(CLEAN);
    }

    @Test
    void alertsCannotBeChanged() throws Exception {
        subscribe(CLEAN);
        importDataset(LISTS_SARAH);
        worker.tick();

        assertThatThrownBy(() -> jdbc.update("UPDATE monitoring_alert SET decision_after = 'PASS'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM monitoring_alert")).isInstanceOf(DataAccessException.class);
    }

    // ---------- keeping the trigger, and forcing a full re-screen ----------

    @Test
    void anImportLeavesItsTriggerBehindUntilItIsScreened() {
        importDataset(LISTS_SARAH);
        assertThat(pendingRuns()).isEqualTo(1);

        worker.tick();

        assertThat(pendingRuns()).isZero();
    }

    @Test
    void anUnchangedImportLeavesNoTrigger() {
        assertThat(imports.importLatest().outcome()).isEqualTo(ImportOutcome.UNCHANGED);

        assertThat(pendingRuns()).isZero();
    }

    @Test
    void importsThatPiledUpAreAllScreened() throws Exception {
        subscribe(CLEAN);
        importDataset(person("NK-zebulon", "Zebulon Quixote", "1950-01-01"));
        importDataset(LISTS_SARAH);

        worker.tick();

        assertThat(alerts()).hasSize(1);
        assertThat(pendingRuns()).isZero();
    }

    /** The version to compare against is gone, so nothing can be assumed about what changed. */
    @Test
    void aBaseVersionThatWasPrunedForcesAFullReScreen() throws Exception {
        subscribe(CLEAN);
        jdbc.update("UPDATE monitoring_state SET last_dataset_version_id = -1");

        importDataset(person("NK-zebulon", "Zebulon Quixote", "1950-01-01"));
        worker.tick();

        assertThat(checks()).isEqualTo(2);
        assertThat(triggers()).contains("FULL_RESCREEN");
    }

    @Test
    void aChangedEngineOrMatcherForcesAFullReScreenWithNoNewDataset() throws Exception {
        subscribe(CLEAN);
        jdbc.update("UPDATE monitoring_state SET fingerprint = 'an older matcher'");

        worker.tick();

        assertThat(checks()).isEqualTo(2);
        assertThat(triggers()).contains("FULL_RESCREEN");

        worker.tick();
        assertThat(checks()).isEqualTo(2);
    }

    // ---------- the periodic re-check ----------

    @Test
    void everySubscriptionIsReCheckedWhenItsIntervalIsUp() throws Exception {
        subscribe(CLEAN);

        clock.advance(Duration.ofDays(29));
        worker.tick();
        assertThat(checks()).isEqualTo(1);

        clock.advance(Duration.ofDays(2));
        worker.tick();
        assertThat(checks()).isEqualTo(2);
        assertThat(triggers()).contains("PERIODIC");

        worker.tick();
        assertThat(checks()).isEqualTo(2);
    }

    /** Found by the periodic re-check, because no sanctions import could have seen it. */
    @Test
    void aChangeAtCompaniesHouseIsFoundByThePeriodicReCheck() throws Exception {
        subscribe(CLEAN);
        wm.stubFor(get(urlPathEqualTo("/company/" + CLEAN + "/officers"))
                .willReturn(okJson(Fixtures.read("checks/sanctioned-officers.json"))));

        clock.advance(Duration.ofDays(31));
        worker.tick();

        assertThat(alerts()).hasSize(1);
        assertThat(alerts().get(0).path("trigger").asText()).isEqualTo("PERIODIC");
    }

    /** jobs-per-tick is 2 here, which is what spreads the work out. */
    @Test
    void aPassRunsOnlySoManyReChecksAndTheRestFollow() throws Exception {
        for (String number : List.of("30000001", "30000002", "30000003")) {
            stubCompany(number, "clean");
            subscribe(number);
        }

        clock.advance(Duration.ofDays(31));
        worker.tick();
        assertThat(checks()).isEqualTo(5);

        worker.tick();
        assertThat(checks()).isEqualTo(6);
    }

    /** An outage says nothing about the company, so the re-check waits rather than reads as clean. */
    @Test
    void aCompaniesHouseOutageLeavesTheReCheckQueued() throws Exception {
        subscribe(CLEAN);
        importDataset(LISTS_SARAH);
        cache.clearAll();
        wm.stubFor(
                get(urlPathEqualTo("/company/" + CLEAN)).willReturn(aResponse().withStatus(503)));

        worker.tick();

        assertThat(checks()).isEqualTo(1);
        assertThat(alerts()).isEmpty();
        assertThat(count("monitoring_job")).isEqualTo(1);

        stubCompany(CLEAN, "clean");
        clock.advance(Duration.ofHours(2));
        worker.tick();

        assertThat(alerts()).hasSize(1);
        assertThat(count("monitoring_job")).isZero();
    }

    // ---------- found by code review: failure and concurrency ----------

    /**
     * The client serves an expired cache entry during an outage, so a check can complete on old data. It
     * is stored and flagged, but it is not a re-check, or the next one would be 30 days away.
     */
    @Test
    void aReCheckThatRestedOnStaleDataIsTriedAgain() throws Exception {
        subscribe(CLEAN);
        clock.advance(Duration.ofDays(31));
        wm.stubFor(get(urlPathMatching("/company/" + CLEAN + ".*"))
                .willReturn(aResponse().withStatus(503)));

        worker.tick();

        assertThat(checks()).isEqualTo(2);
        assertThat(count("monitoring_job")).isEqualTo(1);

        stubCompany(CLEAN, "clean");
        clock.advance(Duration.ofHours(2));
        worker.tick();

        assertThat(checks()).isEqualTo(3);
        assertThat(count("monitoring_job")).isZero();
    }

    /** One job that always fails must not hold up everyone behind it, or anything after it. */
    @Test
    void aJobThatFailsUnexpectedlyDoesNotBlockTheOthers() throws Exception {
        stubCompany("30000001", "clean");
        subscribe("30000001");
        subscribe(CLEAN);
        jdbc.update(
                "UPDATE monitoring_subscription SET company_number = 'not a number' WHERE company_number = '30000001'");
        clock.advance(Duration.ofDays(31));

        worker.tick();

        assertThat(checks()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_job WHERE last_error IS NOT NULL", Long.class))
                .isEqualTo(1);

        // It waits its turn rather than being tried again straight away.
        worker.tick();
        assertThat(jdbc.queryForObject("SELECT attempts FROM monitoring_job", Integer.class))
                .isEqualTo(1);
    }

    /** Otherwise the second worker finds a job in the way, adds nothing, and the first has used an older list. */
    @Test
    void aHitArrivingWhileThatCompanyIsBeingReCheckedIsNotLost() throws Exception {
        subscribe(CLEAN);
        jdbc.update(
                """
                INSERT INTO monitoring_job (subscription_id, trigger, created_at, next_attempt_at)
                SELECT id, 'PERIODIC', now(), now() FROM monitoring_subscription
                """);
        CountDownLatch claimed = new CountDownLatch(1);
        CompletableFuture<Void> running =
                CompletableFuture.runAsync(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT id FROM monitoring_job FOR UPDATE");
                    claimed.countDown();
                    pause();
                    jdbc.update("DELETE FROM monitoring_job");
                }));
        claimed.await();

        importDataset(LISTS_SARAH);
        worker.tick();
        running.join();

        assertThat(alerts()).hasSize(1);
    }

    /** A worker takes the job and then the subscription, so stopping must take them in the same order. */
    @Test
    void unsubscribingWhileAJobIsRunningWaitsForItRatherThanDeadlocking() throws Exception {
        subscribe(CLEAN);
        jdbc.update(
                """
                INSERT INTO monitoring_job (subscription_id, trigger, created_at, next_attempt_at)
                SELECT id, 'PERIODIC', now(), now() FROM monitoring_subscription
                """);
        CountDownLatch claimed = new CountDownLatch(1);
        CompletableFuture<Void> running =
                CompletableFuture.runAsync(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT id FROM monitoring_job FOR UPDATE");
                    claimed.countDown();
                    pause();
                    jdbc.update("UPDATE monitoring_subscription SET next_recheck_at = next_recheck_at");
                }));
        claimed.await();

        ResponseEntity<Void> stopped = unsubscribe(CLEAN);
        running.join();

        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("monitoring_job")).isZero();
    }

    @Test
    void checksRacingForOneCompanyRaiseOneAlert() throws Exception {
        subscribe(CLEAN);
        importDataset(LISTS_SARAH);

        CompletableFuture.allOf(
                        CompletableFuture.runAsync(
                                () -> http.postForEntity(CHECKS, Map.of("companyNumber", CLEAN), String.class)),
                        CompletableFuture.runAsync(
                                () -> http.postForEntity(CHECKS, Map.of("companyNumber", CLEAN), String.class)))
                .join();

        assertThat(alerts()).hasSize(1);
    }

    /** That check predates the listing, and later deltas will not include it, so nothing would ever say. */
    @Test
    void subscribingAfterTheListHasMovedOnChecksAgainRatherThanWatchingAnOldAnswer() throws Exception {
        String old = runCheck(CLEAN).path("checkId").asText();
        importDataset(LISTS_SARAH);

        JsonNode body = subscribe(CLEAN);

        assertThat(checks()).isEqualTo(2);
        assertThat(body.path("latestCheckId").asText()).isNotEqualTo(old);
        JsonNode latest = json.readTree(
                http.getForObject(CHECKS + "/" + body.path("latestCheckId").asText(), String.class));
        assertThat(latest.path("decision").asText()).isEqualTo("FAIL");
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- webhooks ----------

    @Test
    void aWebhookIsSignedAndCarriesNoNames() throws Exception {
        subscribe(CLEAN);
        importDataset(LISTS_SARAH);
        worker.tick();

        LoggedRequest request = webhookRequests().get(0);
        String body = request.getBodyAsString();
        String timestamp = request.getHeader("X-KYB-Timestamp");
        assertThat(request.getHeader("X-KYB-Signature")).isEqualTo("sha256=" + hmac(timestamp + "." + body));

        JsonNode alert = alerts().get(0);
        JsonNode event = json.readTree(body);
        assertThat(request.getHeader("X-KYB-Event-Id"))
                .isEqualTo(alert.path("alertId").asText());
        assertThat(event.path("eventId")).isEqualTo(alert.path("alertId"));
        assertThat(event.path("type").asText()).isEqualTo("MATCHES_CHANGED");
        assertThat(event.path("companyNumber").asText()).isEqualTo(CLEAN);
        assertThat(event.path("checkId")).isEqualTo(alert.path("checkId"));
        assertThat(event.path("decisionBefore").asText()).isEqualTo("PASS");
        assertThat(event.path("decisionAfter").asText()).isEqualTo("FAIL");
        assertThat(event.path("entitiesAdded").toString()).contains("NK-sarah");
        assertThat(body).doesNotContainIgnoringCase("jones").doesNotContainIgnoringCase("sarah j");
    }

    @Test
    void aFailedWebhookIsRetriedWithTheSameEventAndBytesAndNotAfterItSucceeds() throws Exception {
        wm.stubFor(post(urlPathEqualTo(WEBHOOK)).willReturn(aResponse().withStatus(500)));
        subscribe(CLEAN);
        importDataset(LISTS_SARAH);
        worker.tick();
        assertThat(webhookRequests()).hasSize(1);

        // Not due yet: the backoff has not passed.
        webhooks.deliverDue();
        assertThat(webhookRequests()).hasSize(1);

        wm.stubFor(post(urlPathEqualTo(WEBHOOK)).willReturn(aResponse().withStatus(200)));
        clock.advance(Duration.ofHours(2));
        webhooks.deliverDue();

        List<LoggedRequest> requests = webhookRequests();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).getHeader("X-KYB-Event-Id"))
                .isEqualTo(requests.get(0).getHeader("X-KYB-Event-Id"));
        assertThat(requests.get(1).getBodyAsString()).isEqualTo(requests.get(0).getBodyAsString());

        clock.advance(Duration.ofHours(2));
        webhooks.deliverDue();
        assertThat(webhookRequests()).hasSize(2);
    }

    @Test
    void aWebhookThatKeepsFailingStopsAfterItsAttemptsAndTheAlertStays() throws Exception {
        wm.stubFor(post(urlPathEqualTo(WEBHOOK)).willReturn(aResponse().withStatus(500)));
        subscribe(CLEAN);
        importDataset(LISTS_SARAH);
        worker.tick();

        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofHours(2));
            webhooks.deliverDue();
        }

        assertThat(webhookRequests()).hasSize(10);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_outbox", String.class))
                .isEqualTo("FAILED");
        assertThat(alerts()).hasSize(1);
    }

    @Test
    void anUnreachableReceiverCountsAsAFailureNotACrash() throws Exception {
        wm.stubFor(post(urlPathEqualTo(WEBHOOK))
                .willReturn(
                        aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
        subscribe(CLEAN);
        importDataset(LISTS_SARAH);

        worker.tick();

        assertThat(jdbc.queryForObject("SELECT status FROM webhook_outbox", String.class))
                .isEqualTo("PENDING");
    }

    // ---------- several workers at once ----------

    @Test
    void workersRunningAtOnceDoNotRunTheSameReCheckTwice() throws Exception {
        List<String> numbers = List.of("30000001", "30000002", "30000003", "30000004");
        for (String number : numbers) {
            stubCompany(number, "clean");
            subscribe(number);
        }
        clock.advance(Duration.ofDays(31));
        jdbc.update(
                """
                INSERT INTO monitoring_job (subscription_id, trigger, created_at, next_attempt_at)
                SELECT id, 'PERIODIC', now(), now() - interval '1 year' FROM monitoring_subscription
                """);

        CompletableFuture.allOf(
                        CompletableFuture.runAsync(this::drainJobs),
                        CompletableFuture.runAsync(this::drainJobs),
                        CompletableFuture.runAsync(this::drainJobs))
                .join();

        assertThat(count("monitoring_job")).isZero();
        assertThat(checks()).isEqualTo(numbers.size() * 2L);
    }

    @Test
    void workersRunningAtOnceDoNotSendTheSameWebhookTwice() throws Exception {
        wm.stubFor(post(urlPathEqualTo(WEBHOOK)).willReturn(aResponse().withStatus(500)));
        subscribe(CLEAN);
        subscribe(SANCTIONED);
        importDataset(LISTS_SARAH);
        importWithout("NK-ivan");
        worker.tick();
        wm.resetRequests();
        wm.stubFor(post(urlPathEqualTo(WEBHOOK))
                .willReturn(aResponse().withStatus(200).withFixedDelay(200)));
        clock.advance(Duration.ofHours(2));

        CompletableFuture.allOf(
                        CompletableFuture.runAsync(webhooks::deliverDue),
                        CompletableFuture.runAsync(webhooks::deliverDue),
                        CompletableFuture.runAsync(webhooks::deliverDue))
                .join();

        long alerts = count("monitoring_alert");
        assertThat(alerts).isPositive();
        assertThat(webhookRequests()).hasSize((int) alerts);
    }

    // ---------- helpers ----------

    private void drainJobs() {
        for (int i = 0; i < 4; i++) {
            worker.processJobs();
        }
    }

    private static String person(String id, String name, String birthDate) {
        return ("{\"id\":\"%s\",\"caption\":\"%s\",\"schema\":\"Person\",\"datasets\":[\"eu_fsf\"],"
                        + "\"properties\":{\"name\":[\"%s\"],\"birthDate\":[\"%s\"],\"topics\":[\"sanction\"]},\"target\":true}\n")
                .formatted(id, name, name, birthDate);
    }

    private void importDataset(String extraLines) {
        publish(Fixtures.read(FIXTURE) + extraLines);
    }

    private void importWithout(String entityId) {
        publish(Arrays.stream(Fixtures.read(FIXTURE).split("\\R"))
                .filter(line -> !line.contains("\"" + entityId + "\""))
                .collect(Collectors.joining("\n", "", "\n")));
    }

    private void publish(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OpenSanctionsStubs.publish(
                wm.getRuntimeInfo().getWireMock(),
                wm.baseUrl(),
                "mon-" + (++datasets),
                bytes,
                OpenSanctionsStubs.sha1(bytes));
        assertThat(imports.importLatest().outcome()).isEqualTo(ImportOutcome.COMPLETED);
    }

    private ResponseEntity<String> subscribeResponse(String number) {
        return http.postForEntity(SUBSCRIPTIONS, Map.of("companyNumber", number), String.class);
    }

    private JsonNode subscribe(String number) throws Exception {
        ResponseEntity<String> response = subscribeResponse(number);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return json.readTree(response.getBody());
    }

    private ResponseEntity<Void> unsubscribe(String number) {
        return http.exchange(SUBSCRIPTIONS + "/" + number, HttpMethod.DELETE, null, Void.class);
    }

    private JsonNode runCheck(String number) throws Exception {
        ResponseEntity<String> response = http.postForEntity(CHECKS, Map.of("companyNumber", number), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return json.readTree(response.getBody());
    }

    private List<JsonNode> alerts() throws Exception {
        List<JsonNode> alerts = new ArrayList<>();
        json.readTree(http.getForObject(ALERTS, String.class)).forEach(alerts::add);
        return alerts;
    }

    private long checks() {
        return count("kyb_check");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long pendingRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM monitoring_run WHERE finished_at IS NULL", Long.class);
    }

    /** Why each stored check ran. The test clock is frozen, so they cannot be told apart by time. */
    private List<String> triggers() {
        return jdbc.queryForList("SELECT request->>'trigger' FROM kyb_check", String.class);
    }

    private List<LoggedRequest> webhookRequests() {
        return wm.findAll(postRequestedFor(urlPathEqualTo(WEBHOOK)));
    }

    private static String hmac(String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void stubCompany(String number, String fixture) {
        String base = "/company/" + number;
        wm.stubFor(get(urlPathEqualTo(base))
                .willReturn(okJson(Fixtures.read("checks/" + fixture + "-profile.json")
                        .replace("\"company_number\": \"07654321\"", "\"company_number\": \"" + number + "\""))));
        wm.stubFor(get(urlPathEqualTo(base + "/officers"))
                .willReturn(okJson(Fixtures.read("checks/" + fixture + "-officers.json"))));
        wm.stubFor(get(urlPathEqualTo(base + "/persons-with-significant-control"))
                .willReturn(okJson(Fixtures.read("checks/" + fixture + "-pscs.json"))));
        wm.stubFor(
                get(urlPathEqualTo(base + "/exemptions")).willReturn(aResponse().withStatus(404)));
    }

    private static void stubParentOwnedBy(String number, String owner, int year, int month) {
        String base = "/company/" + number;
        wm.stubFor(get(urlPathEqualTo(base))
                .willReturn(okJson(Fixtures.read("checks/clean-profile.json")
                        .replace("07654321", number)
                        .replace("BRIGHT BAKERY LTD", "HARBOUR HOLDINGS LTD"))));
        wm.stubFor(get(urlPathEqualTo(base + "/persons-with-significant-control"))
                .willReturn(okJson(
                        """
                        {"items":[{"name":"%s","kind":"individual-person-with-significant-control",
                          "date_of_birth":{"month":%d,"year":%d},"notified_on":"2016-06-30",
                          "natures_of_control":["ownership-of-shares-75-to-100-percent"]}],"total_results":1}
                        """
                                .formatted(owner, month, year))));
        wm.stubFor(
                get(urlPathEqualTo(base + "/exemptions")).willReturn(aResponse().withStatus(404)));
    }
}
