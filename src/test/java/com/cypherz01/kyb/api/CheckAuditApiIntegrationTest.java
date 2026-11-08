package com.cypherz01.kyb.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.sanctions.ImportOutcome;
import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.support.ApiIntegrationTest;
import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M6 acceptance, end to end: a replay reproduces the stored result even after a new sanctions
 * dataset has been imported and the upstream sources have changed or gone. This class runs on the
 * real clock on purpose, as its sub-microsecond precision is one way a replay could fail to match.
 */
@ApiIntegrationTest
class CheckAuditApiIntegrationTest {

    static final String CHECKS = "/api/v1/checks";
    static final String SANCTIONED = "01234567";
    static final String CLEAN = "07654321";
    static final String FIXTURE = "sanctions/entities-screening.ftm.json";

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
        importDataset("check-v1", Fixtures.bytes(FIXTURE));
        stubCompany(SANCTIONED, "sanctioned");
        stubCompany(CLEAN, "clean");
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/search-empty.json"))));
    }

    // ---------- acceptance ----------

    @Test
    void aReplayIsIdenticalAfterANewDatasetAndWithTheSourcesGone() throws Exception {
        String stored = runCheck(SANCTIONED);
        JsonNode original = json.readTree(stored);
        assertThat(original.path("decision").asText()).isEqualTo("FAIL");

        // The director comes off the list, and a new check now reaches a different answer...
        importDataset("check-v2", withoutEntity("NK-ivan"));
        JsonNode today = json.readTree(runCheck(SANCTIONED));
        assertThat(today.path("decision").asText()).isNotEqualTo("FAIL");
        assertThat(today.path("sources").get(2).path("datasetVersion").asText()).isEqualTo("check-v2");

        // ...and with every upstream gone and the caches empty, the old check still reproduces.
        wm.resetAll();
        cache.clearAll();
        JsonNode replay = replay(original.path("checkId").asText());

        assertThat(replay.path("identical").asBoolean()).as(replay.toString()).isTrue();
        assertThat(replay.path("differences").size()).isZero();
        assertThat(replay.path("datasetVersion").asText()).isEqualTo("check-v1");
        assertThat(replay.path("original").path("decision").asText()).isEqualTo("FAIL");
        assertThat(replay.path("replayed").path("decision").asText()).isEqualTo("FAIL");
        assertThat(replay.path("replayed").path("score").asInt())
                .isEqualTo(original.path("score").asInt());
        assertThat(replay.path("result")).isEqualTo(original);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    // ---------- replay across the shapes a result can take ----------

    @Test
    void aCleanCheckReplaysIdentically() throws Exception {
        assertReplaysIdentically(runCheck(CLEAN));
    }

    @Test
    void aCheckFromCachedDataReplaysIdentically() throws Exception {
        runCheck(CLEAN);

        String second = runCheck(CLEAN);

        assertThat(json.readTree(second)
                        .path("sources")
                        .get(0)
                        .path("fromCache")
                        .asBoolean())
                .isTrue();
        assertReplaysIdentically(second);
    }

    @Test
    void aCheckDuringAnFcaOutageReplaysAsUnavailable() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(aResponse().withStatus(503)));

        String stored = runCheck(CLEAN);

        assertThat(json.readTree(stored).path("regulatory").path("availability").asText())
                .isEqualTo("UNAVAILABLE");
        JsonNode fca = snapshot(trail(idOf(stored)), "fca-register", "assessment");
        assertThat(fca.path("fetchedAt").isNull()).isTrue();
        assertThat(fca.path("payload").path("unavailableReason").asText()).isNotBlank();
        assertReplaysIdentically(stored);
    }

    @Test
    void anAuthorisedFirmMatchReplaysIdentically() throws Exception {
        String number = "12345678";
        String base = "/company/" + number;
        wm.stubFor(get(urlPathEqualTo(base))
                .willReturn(okJson(Fixtures.read("checks/clean-profile.json")
                        .replace("07654321", number)
                        .replace("BRIGHT BAKERY LTD", "ACME PAYMENTS LTD"))));
        wm.stubFor(get(urlPathEqualTo(base + "/officers"))
                .willReturn(okJson(Fixtures.read("checks/clean-officers.json"))));
        wm.stubFor(get(urlPathEqualTo(base + "/persons-with-significant-control"))
                .willReturn(okJson(Fixtures.read("checks/clean-pscs.json"))));
        wm.stubFor(
                get(urlPathEqualTo(base + "/exemptions")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/search-acme.json"))));
        wm.stubFor(get(urlPathEqualTo("/Firm/123456")).willReturn(okJson(Fixtures.read("fca/firm-123456.json"))));

        String stored = runCheck(number);

        assertThat(json.readTree(stored)
                        .path("regulatory")
                        .path("authorisedFirm")
                        .path("frn")
                        .asText())
                .isEqualTo("123456");
        assertReplaysIdentically(stored);
    }

    /** JSONB would sort these two by key length, and the exemption note lists them in order. */
    @Test
    void sourceFieldOrderSurvivesStorage() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/company/" + CLEAN + "/persons-with-significant-control"))
                .willReturn(okJson("{\"items\":[],\"total_results\":0}")));
        wm.stubFor(
                get(urlPathEqualTo("/company/" + CLEAN + "/exemptions"))
                        .willReturn(
                                okJson(
                                        """
                {"exemptions":{
                  "psc_exempt_as_trading_on_uk_regulated_market":{
                    "exemption_type":"psc-exempt-as-trading-on-uk-regulated-market","items":[{"exempt_from":"2016-06-30"}]},
                  "a_short_key":{"exemption_type":"a-short-key","items":[{"exempt_from":"2016-06-30"}]}
                }}
                """)));

        String stored = runCheck(CLEAN);

        JsonNode exemptions = snapshot(trail(idOf(stored)), "companies-house", "exemptions");
        assertThat(exemptions.path("payload").path("exemptions").fieldNames().next())
                .isEqualTo("psc_exempt_as_trading_on_uk_regulated_market");
        assertReplaysIdentically(stored);
    }

    @Test
    void aStoredCheckThatNoLongerMatchesIsReportedWithTheDifference() throws Exception {
        String id = idOf(runCheck(CLEAN));
        String altered = copyCheck(
                id,
                "replace(response::text, '\"engineVersion\":\"1.0.0', '\"engineVersion\":\"0.9.0')::json",
                "'0.9.0'",
                true);

        JsonNode replay = replay(altered);

        assertThat(replay.path("identical").asBoolean()).isFalse();
        assertThat(replay.path("differences"))
                .extracting(d -> d.path("path").asText())
                .containsExactly("engineVersion");
        JsonNode difference = replay.path("differences").get(0);
        assertThat(difference.path("original").asText()).startsWith("0.9.0");
        assertThat(difference.path("replayed").asText()).startsWith("1.0.0");
        assertThat(replay.path("original").path("engineVersion").asText()).isEqualTo("0.9.0");
        // The outcome itself is unchanged, and the two summaries show that.
        assertThat(replay.path("replayed").path("decision").asText())
                .isEqualTo(replay.path("original").path("decision").asText());
    }

    // ---------- what a replay leaves behind ----------

    @Test
    void aReplayChangesNothingButTheAuditTrail() throws Exception {
        String stored = runCheck(SANCTIONED);
        String id = idOf(stored);
        int snapshots = trail(id).path("snapshots").size();

        replay(id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyb_check", Integer.class))
                .isEqualTo(1);
        assertThat(http.getForEntity(CHECKS + "/" + id, String.class).getBody()).isEqualTo(stored);
        assertThat(trail(id).path("snapshots").size()).isEqualTo(snapshots);
    }

    @Test
    void everyReplayIsRecorded() throws Exception {
        String id = idOf(runCheck(SANCTIONED));

        replay(id);
        replay(id);

        JsonNode trail = trail(id);
        assertThat(eventTypes(trail))
                .containsExactly(
                        "CHECK_REQUESTED", "SOURCES_GATHERED", "CHECK_COMPLETED", "CHECK_REPLAYED", "CHECK_REPLAYED");
        JsonNode replayed = trail.path("events").get(3).path("details");
        assertThat(replayed.path("identical").asBoolean()).isTrue();
        assertThat(replayed.path("decision").asText()).isEqualTo("FAIL");
        assertThat(replayed.path("engineVersion").asText()).startsWith("1.0.0");
    }

    // ---------- when a replay is impossible ----------

    @Test
    void aCheckWhoseDatasetWasPrunedCannotBeReplayedAndSaysWhy() throws Exception {
        String prunedCheck = idOf(runCheck(SANCTIONED));
        importDataset("check-v2", withExtraEntity("v2"));
        String retainedCheck = idOf(runCheck(SANCTIONED));
        importDataset("check-v3", withExtraEntity("v3"));
        importDataset("check-v4", withExtraEntity("v4")); // keep-versions = 3, so v1 goes

        ResponseEntity<String> refused = postReplay(prunedCheck);

        assertProblem(refused, 409);
        assertThat(json.readTree(refused.getBody()).path("detail").asText()).contains("check-v1");
        // The check itself and everything it recorded are untouched.
        JsonNode trail = trail(prunedCheck);
        assertThat(trail.path("datasetVersion").path("label").asText()).isEqualTo("check-v1");
        assertThat(trail.path("datasetVersion").path("status").asText()).isEqualTo("PRUNED");
        assertThat(trail.path("snapshots").size()).isEqualTo(5);
        assertThat(eventTypes(trail)).endsWith("CHECK_REPLAY_REFUSED");
        assertThat(trail.path("events").get(3).path("details").path("reason").asText())
                .contains("check-v1");
        // A check on a version still retained is fine, even though it is no longer the active one.
        assertThat(replay(retainedCheck).path("identical").asBoolean()).isTrue();
    }

    @Test
    void aCheckWithoutSnapshotsCannotBeReplayedAndSaysWhy() throws Exception {
        String legacy = copyCheck(idOf(runCheck(CLEAN)), "response", "engine_version", false);

        ResponseEntity<String> refused = postReplay(legacy);

        assertProblem(refused, 409);
        assertThat(json.readTree(refused.getBody()).path("detail").asText()).contains("snapshots");
    }

    @Test
    void replayingAnUnknownCheckIsNotFound() throws Exception {
        ResponseEntity<String> response = postReplay("3f1c2b7e-0000-4000-8000-000000000000");

        assertProblem(response, 404);
        // The title proves it was this check that was missing, not that the route does not exist.
        assertThat(json.readTree(response.getBody()).path("title").asText()).isEqualTo("Check not found");
    }

    @Test
    void replayingAMalformedIdIsABadRequest() throws Exception {
        assertProblem(postReplay("not-a-uuid"), 400);
    }

    // ---------- the audit trail ----------

    @Test
    void theTrailDescribesTheCheck() throws Exception {
        JsonNode check = json.readTree(runCheck(SANCTIONED));
        String id = check.path("checkId").asText();

        JsonNode trail = trail(id);

        assertThat(trail.path("checkId").asText()).isEqualTo(id);
        assertThat(trail.path("companyNumber").asText()).isEqualTo(SANCTIONED);
        assertThat(trail.path("decision").asText()).isEqualTo("FAIL");
        assertThat(trail.path("score").asInt()).isEqualTo(check.path("score").asInt());
        assertThat(trail.path("requestedAt").asText())
                .isEqualTo(check.path("requestedAt").asText());
        assertThat(trail.path("completedAt").asText())
                .isEqualTo(check.path("completedAt").asText());
        assertThat(trail.path("request").path("companyNumber").asText()).isEqualTo(SANCTIONED);
        assertThat(trail.path("datasetVersion").path("label").asText()).isEqualTo("check-v1");
        assertThat(trail.path("datasetVersion").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(trail.path("scoringConfigVersion").asText())
                .isEqualTo(check.path("scoringConfigVersion").asText());
        assertThat(trail.path("engineVersion").asText())
                .isEqualTo(check.path("engineVersion").asText());
    }

    @Test
    void theTrailHoldsExactlyWhatEachSourceReturned() throws Exception {
        JsonNode trail = trail(idOf(runCheck(SANCTIONED)));

        assertThat(trail.path("snapshots"))
                .extracting(s ->
                        s.path("source").asText() + "/" + s.path("operation").asText())
                .containsExactly(
                        "companies-house/profile",
                        "companies-house/officers",
                        "companies-house/pscs",
                        "companies-house/exemptions",
                        "fca-register/assessment");

        JsonNode profile = snapshot(trail, "companies-house", "profile");
        assertThat(profile.path("payload")).isEqualTo(json.readTree(Fixtures.read("checks/sanctioned-profile.json")));
        assertThat(profile.path("payload").fieldNames().next()).isEqualTo("company_number");
        assertThat(profile.path("fetchedAt").asText()).isNotBlank();
        assertThat(profile.path("fromCache").asBoolean()).isFalse();
        assertThat(profile.path("stale").asBoolean()).isFalse();

        assertThat(snapshot(trail, "companies-house", "officers")
                        .path("payload")
                        .path("items")
                        .size())
                .isEqualTo(json.readTree(Fixtures.read("checks/sanctioned-officers.json"))
                        .path("items")
                        .size());
        // A 404 from /exemptions is an answer, stored as how the mapper reads it.
        assertThat(snapshot(trail, "companies-house", "exemptions")
                        .path("payload")
                        .size())
                .isZero();
        assertThat(snapshot(trail, "fca-register", "assessment")
                        .path("payload")
                        .path("search")
                        .path("Message")
                        .asText())
                .isNotBlank();
    }

    @Test
    void theTrailShowsWhenDataCameFromTheCache() throws Exception {
        String first = runCheck(CLEAN);
        String firstFetch = snapshot(trail(idOf(first)), "companies-house", "profile")
                .path("fetchedAt")
                .asText();

        JsonNode cached = snapshot(trail(idOf(runCheck(CLEAN))), "companies-house", "profile");

        assertThat(cached.path("fromCache").asBoolean()).isTrue();
        assertThat(cached.path("fetchedAt").asText()).isEqualTo(firstFetch);
    }

    @Test
    void theTrailRecordsWhatHappenedInOrder() throws Exception {
        JsonNode check = json.readTree(runCheck(SANCTIONED));

        JsonNode trail = trail(check.path("checkId").asText());

        assertThat(eventTypes(trail)).containsExactly("CHECK_REQUESTED", "SOURCES_GATHERED", "CHECK_COMPLETED");
        JsonNode requested = trail.path("events").get(0);
        assertThat(requested.path("occurredAt").asText())
                .isEqualTo(check.path("requestedAt").asText());
        assertThat(requested.path("details").path("companyNumber").asText()).isEqualTo(SANCTIONED);

        JsonNode gathered = trail.path("events").get(1).path("details");
        assertThat(gathered.path("regulatoryAvailability").asText()).isEqualTo("AVAILABLE");
        assertThat(gathered.path("datasetVersion").asText()).isEqualTo("check-v1");

        JsonNode completed = trail.path("events").get(2);
        assertThat(completed.path("occurredAt").asText())
                .isEqualTo(check.path("completedAt").asText());
        assertThat(completed.path("details").path("decision").asText()).isEqualTo("FAIL");
        assertThat(completed.path("details").path("score").asInt())
                .isEqualTo(check.path("score").asInt());
        assertThat(completed.path("details").path("factors"))
                .extracting(JsonNode::asText)
                .containsExactly("SANCTIONS_STRONG_MATCH");
    }

    @Test
    void aFailedCheckLeavesNoRecord() throws Exception {
        wm.stubFor(
                get(urlPathEqualTo("/company/09999999")).willReturn(aResponse().withStatus(404)));

        assertProblem(postCheck("09999999"), 404);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyb_check", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyb_check_source_snapshot", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class))
                .isZero();
    }

    @Test
    void theTrailOfAnUnknownCheckIsNotFound() throws Exception {
        ResponseEntity<String> response =
                http.getForEntity(CHECKS + "/3f1c2b7e-0000-4000-8000-000000000000/audit", String.class);

        assertProblem(response, 404);
        assertThat(json.readTree(response.getBody()).path("title").asText()).isEqualTo("Check not found");
    }

    @Test
    void theTrailOfAMalformedIdIsABadRequest() throws Exception {
        assertProblem(http.getForEntity(CHECKS + "/not-a-uuid/audit", String.class), 400);
    }

    // ---------- append-only ----------

    @Test
    void theDatabaseRejectsChangesToTheRecord() throws Exception {
        String id = idOf(runCheck(CLEAN));

        for (String statement : List.of(
                "UPDATE kyb_check SET score = 0 WHERE id = ?::uuid",
                "DELETE FROM kyb_check WHERE id = ?::uuid",
                "UPDATE kyb_check_source_snapshot SET stale = TRUE WHERE check_id = ?::uuid",
                "DELETE FROM kyb_check_source_snapshot WHERE check_id = ?::uuid",
                "UPDATE audit_event SET event_type = 'X' WHERE check_id = ?::uuid",
                "DELETE FROM audit_event WHERE check_id = ?::uuid")) {
            assertThatThrownBy(() -> jdbc.update(statement, id))
                    .as(statement)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }
    }

    // ---------- helpers ----------

    private void assertReplaysIdentically(String stored) throws Exception {
        JsonNode original = json.readTree(stored);
        JsonNode replay = replay(original.path("checkId").asText());
        assertThat(replay.path("identical").asBoolean())
                .as(replay.path("differences").toString())
                .isTrue();
        assertThat(replay.path("result")).isEqualTo(original);
    }

    /**
     * Adds a copy of a stored check under a new id: the only way to get a check whose stored result
     * differs from what it would now produce, or that predates snapshots, as the table is append-only.
     */
    private String copyCheck(String id, String responseSql, String engineSql, boolean withSnapshots) {
        String copy = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO kyb_check (id, company_number, requested_at, completed_at, decision, score,
                    sanctions_dataset_version_id, scoring_config_version, engine_version, request, response)
                SELECT ?::uuid, company_number, requested_at, completed_at, decision, score,
                    sanctions_dataset_version_id, scoring_config_version, %s, request,
                    replace((%s)::text, ?, ?)::json
                  FROM kyb_check WHERE id = ?::uuid
                """
                        .formatted(engineSql, responseSql),
                copy,
                id,
                copy,
                id);
        if (withSnapshots) {
            jdbc.update(
                    """
                    INSERT INTO kyb_check_source_snapshot (check_id, source, operation, fetched_at, from_cache, stale, payload)
                    SELECT ?::uuid, source, operation, fetched_at, from_cache, stale, payload
                      FROM kyb_check_source_snapshot WHERE check_id = ?::uuid ORDER BY id
                    """,
                    copy,
                    id);
        }
        return copy;
    }

    private void importDataset(String version, byte[] body) {
        OpenSanctionsStubs.publish(
                wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), version, body, OpenSanctionsStubs.sha1(body));
        assertThat(imports.importLatest().outcome()).isEqualTo(ImportOutcome.COMPLETED);
    }

    private static byte[] withoutEntity(String entityId) {
        return Arrays.stream(Fixtures.read(FIXTURE).split("\\R"))
                .filter(line -> !line.contains("\"" + entityId + "\""))
                .collect(Collectors.joining("\n", "", "\n"))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Different content, so the import does not skip it as unchanged. */
    private static byte[] withExtraEntity(String id) {
        String line =
                "{\"id\":\"NK-extra-%s\",\"caption\":\"Zebulon Quixote\",\"schema\":\"Person\",\"datasets\":[\"eu_fsf\"],"
                                .formatted(id)
                        + "\"properties\":{\"name\":[\"Zebulon Quixote\"],\"topics\":[\"sanction\"]},\"target\":true}\n";
        return (Fixtures.read(FIXTURE) + line).getBytes(StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> postCheck(String companyNumber) {
        return http.postForEntity(CHECKS, Map.of("companyNumber", companyNumber), String.class);
    }

    private String runCheck(String companyNumber) {
        ResponseEntity<String> response = postCheck(companyNumber);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private String idOf(String check) throws Exception {
        return json.readTree(check).path("checkId").asText();
    }

    private ResponseEntity<String> postReplay(String id) {
        return http.postForEntity(CHECKS + "/" + id + "/replay", null, String.class);
    }

    private JsonNode replay(String id) throws Exception {
        ResponseEntity<String> response = postReplay(id);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody());
    }

    private JsonNode trail(String id) throws Exception {
        ResponseEntity<String> response = http.getForEntity(CHECKS + "/" + id + "/audit", String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody());
    }

    private static JsonNode snapshot(JsonNode trail, String source, String operation) {
        for (JsonNode s : trail.path("snapshots")) {
            if (s.path("source").asText().equals(source)
                    && s.path("operation").asText().equals(operation)) {
                return s;
            }
        }
        throw new AssertionError("No snapshot " + source + "/" + operation + " in " + trail);
    }

    private static List<String> eventTypes(JsonNode trail) {
        List<String> types = new ArrayList<>();
        trail.path("events").forEach(e -> types.add(e.path("eventType").asText()));
        return types;
    }

    private void assertProblem(ResponseEntity<String> response, int status) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json.readTree(response.getBody()).path("detail").asText()).isNotBlank();
    }

    private static void stubCompany(String number, String fixture) {
        String base = "/company/" + number;
        wm.stubFor(get(urlPathEqualTo(base)).willReturn(okJson(Fixtures.read("checks/" + fixture + "-profile.json"))));
        wm.stubFor(get(urlPathEqualTo(base + "/officers"))
                .willReturn(okJson(Fixtures.read("checks/" + fixture + "-officers.json"))));
        wm.stubFor(get(urlPathEqualTo(base + "/persons-with-significant-control"))
                .willReturn(okJson(Fixtures.read("checks/" + fixture + "-pscs.json"))));
        wm.stubFor(
                get(urlPathEqualTo(base + "/exemptions")).willReturn(aResponse().withStatus(404)));
    }
}
