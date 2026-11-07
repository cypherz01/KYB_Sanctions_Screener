package com.cypherz01.kyb.sanctions;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.MutableClock;
import com.cypherz01.kyb.support.MutableClockConfig;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.cypherz01.kyb.support.PostgresTestcontainer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M2 acceptance: streaming import of an OpenSanctions-shaped file into Postgres, served by
 * WireMock exactly as upstream serves it (index, then {@code latest}, then a 307 to the artifact).
 * Covers loading of entities, aliases and DOBs (spec §12), idempotency, the atomic swap, retention,
 * and every failure path leaving the active version untouched.
 */
@SpringBootTest
@Import({PostgresTestcontainer.class, MutableClockConfig.class})
class SanctionsImportIntegrationTest {

    static final String V1 = "sanctions/entities-v1.ftm.json";
    static final String V2 = "sanctions/entities-v2.ftm.json";
    static final String BROKEN = "sanctions/entities-broken.ftm.json";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void sanctions(DynamicPropertyRegistry registry) {
        registry.add("kyb.sanctions.index-url", () -> wm.baseUrl() + OpenSanctionsStubs.INDEX_PATH);
        registry.add("kyb.sanctions.batch-size", () -> 2); // several batches even for tiny fixtures
        registry.add("kyb.sanctions.keep-versions", () -> 2);
        registry.add("kyb.sanctions.max-malformed-lines", () -> 1);
        registry.add("kyb.sanctions.cron", () -> "-");
    }

    @Autowired
    SanctionsImportService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        wm.resetAll();
        jdbc.update("DELETE FROM sanctions_dataset_version"); // cascades to entities and names
        clock.set(MutableClockConfig.T0);
    }

    // ---------- what gets loaded ----------

    @Test
    void importsOnlyScreenableEntitiesOfTheImportedSchemas() {
        publish("v1", V1);

        ImportResult result = service.importLatest();

        assertThat(result.outcome()).isEqualTo(ImportOutcome.COMPLETED);
        assertThat(result.versionLabel()).isEqualTo("v1");
        assertThat(result.entityCount()).isEqualTo(5);
        assertThat(result.nameCount()).isEqualTo(9);
        assertThat(result.malformedLines()).isEqualTo(1); // the truncated last line; the blank line is ignored
        assertThat(jdbc.queryForList(
                        "SELECT external_id FROM sanctions_entity WHERE dataset_version_id = ?",
                        String.class,
                        result.versionId()))
                .containsExactlyInAnyOrder(
                        "NK-person-ivan", "NK-co-acme", "NK-org-front", "NK-le-shell", "NK-person-maria");
    }

    @Test
    void loadsNamesAndAliasesWithTheirKind() {
        publish("v1", V1);
        service.importLatest();

        List<Map<String, Object>> names = jdbc.queryForList(
                """
                SELECT n.name_original, n.name_kind, n.is_alias, n.dataset_version_id = e.dataset_version_id AS same_version
                  FROM sanctions_name n JOIN sanctions_entity e ON e.id = n.entity_id
                 WHERE e.external_id = 'NK-person-ivan' ORDER BY n.id
                """);

        assertThat(names)
                .extracting(r -> r.get("name_original"), r -> r.get("name_kind"), r -> r.get("is_alias"))
                .containsExactly(
                        tuple("Ivan Petrov", "PRIMARY", false),
                        tuple("Иван Петров", "PRIMARY", false),
                        tuple("Ivan Petroff", "ALIAS", true),
                        tuple("Ivan Sidorov", "PREVIOUS", true));
        assertThat(names).allSatisfy(r -> assertThat(r.get("same_version")).isEqualTo(true));
    }

    @Test
    void composesTheNameOfAPersonPublishedOnlyAsNameParts() {
        publish("v1", V1);
        service.importLatest();

        assertThat(namesOf("NK-person-maria")).containsExactly("Maria Elena Gonzalez");
    }

    @Test
    void loadsBirthDatesIncludingPartialOnes() {
        publish("v1", V1);
        service.importLatest();

        assertThat(array("birth_dates", "NK-person-ivan")).containsExactly("1971-03-14", "1971");
        assertThat(array("birth_dates", "NK-person-maria")).containsExactly("1980-05");
        assertThat(array("birth_dates", "NK-co-acme")).isEmpty();
    }

    @Test
    void loadsCountriesNationalitiesListsAndProgrammes() {
        publish("v1", V1);
        service.importLatest();

        assertThat(array("nationalities", "NK-person-ivan")).containsExactly("ru", "cy");
        assertThat(array("countries", "NK-co-acme")).containsExactly("ae", "ru");
        assertThat(array("source_lists", "NK-person-ivan")).containsExactly("gb_fcdo_sanctions", "eu_fsf");
        assertThat(array("program_ids", "NK-person-ivan")).containsExactly("GB-RUS", "EU-UKR");
        assertThat(array("topics", "NK-org-front")).containsExactly("export.control", "sanction");
    }

    @Test
    void keepsTheRawEntityUntouched() {
        publish("v1", V1);
        service.importLatest();

        String weakAlias = jdbc.queryForObject(
                "SELECT raw->'properties'->'weakAlias'->>0 FROM sanctions_entity WHERE external_id = 'NK-person-ivan'",
                String.class);

        // not screened, but still on record as published
        assertThat(weakAlias).isEqualTo("Vanya");
    }

    // ---------- dataset versions ----------

    @Test
    void recordsTheDatasetVersion() {
        publish("v1", V1);

        ImportResult result = service.importLatest();
        DatasetVersion active = service.status().active();

        assertThat(active.id()).isEqualTo(result.versionId());
        assertThat(active.versionLabel()).isEqualTo("v1");
        assertThat(active.sourceUrl()).isEqualTo(wm.baseUrl() + OpenSanctionsStubs.LATEST_FILE_PATH);
        assertThat(active.checksum()).isEqualTo(OpenSanctionsStubs.sha1(Fixtures.bytes(V1)));
        assertThat(active.entityCount()).isEqualTo(5);
        assertThat(active.nameCount()).isEqualTo(9);
        assertThat(active.startedAt()).isEqualTo(MutableClockConfig.T0);
        assertThat(active.finishedAt()).isEqualTo(MutableClockConfig.T0);
        assertThat(active.status()).isEqualTo(DatasetStatus.COMPLETED);
        assertThat(active.active()).isTrue();
        assertThat(active.error()).isNull();
    }

    @Test
    void reimportingAnUnchangedExportIsANoOp() {
        publish("v1", V1);
        long first = service.importLatest().versionId();
        clock.advance(Duration.ofDays(1));

        ImportResult second = service.importLatest();

        assertThat(second.outcome()).isEqualTo(ImportOutcome.UNCHANGED);
        assertThat(service.status().active().id()).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM sanctions_entity")).isEqualTo(5);
        assertThat(statusOf(second.versionId())).isEqualTo("SKIPPED");
        // the published checksum matched, so the file was not even downloaded again
        wm.verify(1, getRequestedFor(urlPathEqualTo(OpenSanctionsStubs.artifactPath("v1"))));
    }

    @Test
    void aNewExportBecomesActiveAndThePreviousVersionIsKept() {
        publish("v1", V1);
        long v1 = service.importLatest().versionId();
        wm.resetAll();
        publish("v2", V2);

        ImportResult v2 = service.importLatest();

        assertThat(v2.outcome()).isEqualTo(ImportOutcome.COMPLETED);
        assertThat(service.status().active().id()).isEqualTo(v2.versionId());
        assertThat(count("SELECT count(*) FROM sanctions_dataset_version WHERE is_active"))
                .isEqualTo(1);
        // previous version kept intact for replay (M6), just no longer active
        assertThat(statusOf(v1)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM sanctions_entity WHERE dataset_version_id = " + v1))
                .isEqualTo(5);
        assertThat(count("SELECT count(*) FROM sanctions_entity WHERE dataset_version_id = " + v2.versionId()))
                .isEqualTo(3);
    }

    @Test
    void versionsBeyondRetentionArePrunedButStayOnRecord() {
        publish("v1", V1);
        long v1 = service.importLatest().versionId();
        wm.resetAll();
        publish("v2", V2);
        long v2 = service.importLatest().versionId();
        wm.resetAll();
        publish("v3", V1); // same bytes as v1, but v1 is no longer active, so it is a real change

        ImportResult v3 = service.importLatest();

        assertThat(v3.outcome()).isEqualTo(ImportOutcome.COMPLETED);
        assertThat(statusOf(v1)).isEqualTo("PRUNED"); // keep-versions = 2
        assertThat(count("SELECT count(*) FROM sanctions_entity WHERE dataset_version_id = " + v1))
                .isZero();
        assertThat(count("SELECT count(*) FROM sanctions_name WHERE dataset_version_id = " + v1))
                .isZero();
        assertThat(statusOf(v2)).isEqualTo("COMPLETED");
        assertThat(service.status().active().id()).isEqualTo(v3.versionId());
    }

    // ---------- failures never disturb the active version ----------

    @Test
    void checksumMismatchFailsAndRemovesWhatWasLoaded() {
        publish("v2", V2);
        long good = service.importLatest().versionId();
        wm.resetAll();
        // 5 entities in batches of 2: batches are committed before the checksum can be verified
        OpenSanctionsStubs.publish(
                wm.getRuntimeInfo().getWireMock(),
                wm.baseUrl(),
                "v-bad",
                Fixtures.bytes(V1),
                "0000000000000000000000000000000000000000");

        ImportResult bad = service.importLatest();

        assertThat(bad.outcome()).isEqualTo(ImportOutcome.FAILED);
        assertThat(bad.message()).containsIgnoringCase("checksum");
        assertThat(count("SELECT count(*) FROM sanctions_entity WHERE dataset_version_id = " + bad.versionId()))
                .isZero();
        assertThat(service.status().active().id()).isEqualTo(good);
        DatasetVersion last = service.status().lastImport();
        assertThat(last.id()).isEqualTo(bad.versionId());
        assertThat(last.status()).isEqualTo(DatasetStatus.FAILED);
        assertThat(last.error()).containsIgnoringCase("checksum");
        assertThat(last.finishedAt()).isNotNull();
    }

    @Test
    void downloadFailureFailsAndKeepsThePreviousVersionActive() {
        publish("v1", V1);
        long good = service.importLatest().versionId();
        wm.resetAll();
        publish("v2", V2);
        wm.stubFor(get(urlPathEqualTo(OpenSanctionsStubs.artifactPath("v2")))
                .willReturn(aResponse().withStatus(500)));

        ImportResult result = service.importLatest();

        assertThat(result.outcome()).isEqualTo(ImportOutcome.FAILED);
        assertThat(service.status().active().id()).isEqualTo(good);
    }

    @Test
    void indexFailureWithNothingImportedYetLeavesNoActiveVersion() {
        wm.stubFor(get(urlPathEqualTo(OpenSanctionsStubs.INDEX_PATH))
                .willReturn(aResponse().withStatus(503)));

        ImportResult result = service.importLatest();

        assertThat(result.outcome()).isEqualTo(ImportOutcome.FAILED);
        assertThat(service.status().active()).isNull();
        assertThat(service.status().lastImport().status()).isEqualTo(DatasetStatus.FAILED);
    }

    @Test
    void tooManyMalformedLinesFailTheImport() {
        publish("broken", BROKEN); // 2 malformed lines, limit is 1

        ImportResult result = service.importLatest();

        assertThat(result.outcome()).isEqualTo(ImportOutcome.FAILED);
        assertThat(result.message()).containsIgnoringCase("malformed");
        assertThat(count("SELECT count(*) FROM sanctions_entity")).isZero();
    }

    // ---------- concurrency and crash recovery ----------

    @Test
    void onlyOneImportCanRunAtATime() {
        publish("v1", V1);
        long reserved = service.reserve();

        assertThatThrownBy(service::reserve).isInstanceOf(ImportAlreadyRunningException.class);
        assertThatThrownBy(service::importLatest).isInstanceOf(ImportAlreadyRunningException.class);
        assertThat(service.status().importRunning()).isTrue();

        assertThat(service.run(reserved).outcome()).isEqualTo(ImportOutcome.COMPLETED);
        assertThat(service.status().importRunning()).isFalse();
    }

    @Test
    void interruptedImportsAreRecoveredAsFailedAndTheirRowsRemoved() {
        long orphan = service.reserve();
        // simulate a crash after a batch was committed
        jdbc.update(
                """
                INSERT INTO sanctions_entity (dataset_version_id, external_id, schema_type, caption, birth_dates,
                    countries, nationalities, topics, source_lists, program_ids, raw)
                VALUES (?, 'NK-partial', 'Person', 'Partial', '{}', '{}', '{}', '{}', '{}', '{}', '{}')
                """,
                orphan);

        int recovered = service.recoverInterruptedImports();

        assertThat(recovered).isEqualTo(1);
        assertThat(statusOf(orphan)).isEqualTo("FAILED");
        assertThat(count("SELECT count(*) FROM sanctions_entity")).isZero();
        publish("v1", V1);
        assertThat(service.importLatest().outcome()).isEqualTo(ImportOutcome.COMPLETED); // slot is free again
    }

    // ---------- status and metrics ----------

    @Test
    void statusReportsActiveCountsBySchema() {
        publish("v1", V1);
        long id = service.importLatest().versionId();

        SanctionsStatus status = service.status();

        assertThat(status.activeEntitiesBySchema())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("Person", 2L, "Company", 1L, "Organization", 1L, "LegalEntity", 1L));
        assertThat(status.importRunning()).isFalse();
        assertThat(status.lastImport().id()).isEqualTo(id);
    }

    @Test
    void statusWithNothingImportedIsEmpty() {
        SanctionsStatus status = service.status();

        assertThat(status.active()).isNull();
        assertThat(status.lastImport()).isNull();
        assertThat(status.activeEntitiesBySchema()).isEmpty();
    }

    @Test
    void recordsImportDurationAndActiveEntityCount() {
        publish("v1", V1);
        long before = completedImports();

        service.importLatest();

        assertThat(completedImports()).isEqualTo(before + 1);
        assertThat(meters.get("kyb.sanctions.active.entities").gauge().value()).isEqualTo(5.0);
    }

    // ---------- helpers ----------

    private void publish(String version, String fixture) {
        OpenSanctionsStubs.publish(wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), version, fixture);
    }

    private long completedImports() {
        Timer t =
                meters.find("kyb.sanctions.import").tag("outcome", "completed").timer();
        return t == null ? 0 : t.count();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private String statusOf(long versionId) {
        return jdbc.queryForObject(
                "SELECT status FROM sanctions_dataset_version WHERE id = ?", String.class, versionId);
    }

    private List<String> namesOf(String externalId) {
        return jdbc.queryForList(
                """
                SELECT n.name_original FROM sanctions_name n JOIN sanctions_entity e ON e.id = n.entity_id
                 WHERE e.external_id = ? ORDER BY n.id
                """,
                String.class,
                externalId);
    }

    /** Reads a text[] column of the (single) loaded entity with this external id. */
    private List<String> array(String column, String externalId) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM sanctions_entity WHERE external_id = ?",
                (rs, i) -> toList(rs.getArray(1)),
                externalId);
    }

    private static List<String> toList(Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }
}
