package com.cypherz01.kyb.matching;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cypherz01.kyb.sanctions.ImportOutcome;
import com.cypherz01.kyb.sanctions.ImportResult;
import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.sanctions.SanctionsNameIndexer;
import com.cypherz01.kyb.sanctions.model.NameKind;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.cypherz01.kyb.support.PostgresTestcontainer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M3: two-stage screening (Postgres retrieval, Java scoring) against an imported fixture dataset.
 * Covers retrieval of typo, transliteration and phonetic variants, schema filtering, using only
 * the active version, the stored matcher columns and their re-indexing, and request validation.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
class ScreeningIntegrationTest {

    static final String DATASET = "sanctions/entities-screening.ftm.json";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void sanctions(DynamicPropertyRegistry registry) {
        registry.add("kyb.sanctions.index-url", () -> wm.baseUrl() + OpenSanctionsStubs.INDEX_PATH);
        registry.add("kyb.sanctions.cron", () -> "-");
    }

    @Autowired
    ScreeningService screening;

    @Autowired
    SanctionsImportService imports;

    @Autowired
    SanctionsNameIndexer indexer;

    @Autowired
    MatchingProperties props;

    @Autowired
    JdbcTemplate jdbc;

    long versionId;

    @BeforeEach
    void loadDataset() {
        wm.resetAll();
        jdbc.update("DELETE FROM sanctions_dataset_version");
        versionId = importFixture("screen-v1", DATASET).versionId();
    }

    // ---------- matches and their explanation ----------

    @Test
    void anExactNameIsAStrongMatchWithListsProgrammesAndEvidence() {
        ScreeningResult r = screen("Ivan Petrov", SubjectType.PERSON);

        ScreeningMatch top = r.matches().get(0);
        assertThat(top.sanctionsEntityId()).isEqualTo("NK-ivan");
        assertThat(top.band()).isEqualTo(MatchBand.STRONG);
        assertThat(top.similarity()).isEqualTo(1.0);
        assertThat(top.schema()).isEqualTo("Person");
        assertThat(top.caption()).isEqualTo("Ivan Petrov");
        assertThat(top.lists()).containsExactly("UK FCDO", "EU");
        assertThat(top.programIds()).containsExactly("GB-RUS");
        assertThat(top.birthDates()).containsExactly("1971-03-14");
        assertThat(top.evidence()).isNotEmpty();
        assertThat(r.datasetVersionId()).isEqualTo(versionId);
        assertThat(r.datasetVersionLabel()).isEqualTo("screen-v1");
    }

    @Test
    void sharedFirstNamesAreNotReported() {
        ScreeningResult r = screen("Ivan Petrov", SubjectType.PERSON);

        assertThat(r.matches()).extracting(ScreeningMatch::sanctionsEntityId).doesNotContain("NK-drago", "NK-ivanov");
    }

    @Test
    void reportsEachEntityOnceOnItsBestName() {
        // NK-ivan has "Ivan Petrov" and the alias "Иван Петров": both normalise identically
        ScreeningResult r = screen("Ivan Petrov", SubjectType.PERSON);

        assertThat(r.matches())
                .filteredOn(m -> m.sanctionsEntityId().equals("NK-ivan"))
                .hasSize(1);
        assertThat(r.matches().get(0).nameKind()).isEqualTo(NameKind.PRIMARY);
    }

    @Test
    void aCyrillicSubjectMatchesTheLatinListing() {
        assertThat(topId(screen("Иван Петров", SubjectType.PERSON))).isEqualTo("NK-ivan");
    }

    @Test
    void aTypoIsRetrievedAndMatched() {
        assertThat(topId(screen("Ivan Petrof", SubjectType.PERSON))).isEqualTo("NK-ivan");
    }

    @Test
    void aRomanisationVariantIsRetrievedAndMatched() {
        assertThat(topId(screen("Mohammed Al Rashid", SubjectType.PERSON))).isEqualTo("NK-rashid");
    }

    @Test
    void aliasesAreScreened() {
        ScreeningMatch m = screen("Victor Bout", SubjectType.PERSON).matches().get(0);

        assertThat(m.sanctionsEntityId()).isEqualTo("NK-bout");
        assertThat(m.matchedName()).isEqualTo("Victor Bout");
        assertThat(m.nameKind()).isEqualTo(NameKind.ALIAS);
    }

    @Test
    void organisationsMatchAcrossLegalForms() {
        assertThat(topId(screen("Acme Trading Ltd", SubjectType.ORGANISATION))).isEqualTo("NK-acme");
        assertThat(topId(screen("Romashka LLC", SubjectType.ORGANISATION))).isEqualTo("NK-romashka");
    }

    @Test
    void peopleAreNeverMatchedToCompaniesAndViceVersa() {
        assertThat(screen("Acme Trading", SubjectType.PERSON).matches()).isEmpty();
        assertThat(screen("Ivan Petrov", SubjectType.ORGANISATION).matches()).isEmpty();
        assertThat(topId(screen("Acme Trading", SubjectType.ANY))).isEqualTo("NK-acme");
    }

    @Test
    void aBirthDateDisambiguatesACommonName() {
        assertThat(screening
                        .screen(new ScreeningRequest("John Smith", SubjectType.PERSON, "1980-04", null))
                        .matches())
                .isEmpty();
        assertThat(screening
                        .screen(new ScreeningRequest("John Smith", SubjectType.PERSON, "1955-01", null))
                        .matches()
                        .get(0)
                        .band())
                .isEqualTo(MatchBand.STRONG);
    }

    @Test
    void aCountryIsUsedAsEvidence() {
        ScreeningMatch m = screening
                .screen(new ScreeningRequest("Ivan Petrov", SubjectType.PERSON, null, "RU"))
                .matches()
                .get(0);

        assertThat(m.evidence()).anyMatch(e -> e.contains("matches (RU)"));
    }

    @Test
    void unlabelledSourceListsAreShownByTheirId() {
        assertThat(screen("Zarya Holdings", SubjectType.ORGANISATION)
                        .matches()
                        .get(0)
                        .lists())
                .containsExactly("xx_unknown_list");
    }

    @Test
    void anUnrelatedNameHasNoMatches() {
        assertThat(screen("Zebedee Quux", SubjectType.ANY).matches()).isEmpty();
    }

    @Test
    void reportsTheNormalisedQuery() {
        assertThat(screen("Dr. Ivan PETROV", SubjectType.PERSON).normalisedQuery())
                .isEqualTo("ivan petrov");
    }

    @Test
    void matchesAreBestFirstAndBounded() {
        ScreeningResult r = screen("Ivan Petrov", SubjectType.ANY);

        assertThat(r.matches())
                .extracting(ScreeningMatch::similarity)
                .isSortedAccordingTo((a, b) -> Double.compare(b, a));
        assertThat(r.matches()).hasSizeLessThanOrEqualTo(props.maxMatches());
        assertThat(r.candidatesConsidered())
                .isPositive()
                .isLessThanOrEqualTo(props.retrieval().maxCandidates());
    }

    @Test
    void untypedRequestsDefaultToAny() {
        ScreeningResult r = screening.screen(new ScreeningRequest("Acme Trading", null, null, null));

        assertThat(r.type()).isEqualTo(SubjectType.ANY);
        assertThat(topId(r)).isEqualTo("NK-acme");
    }

    // ---------- dataset versions ----------

    @Test
    void onlyTheActiveVersionIsScreened() {
        wm.resetAll();
        long v2 = importFixture("screen-v2", "sanctions/entities-v2.ftm.json").versionId(); // has no Acme

        ScreeningResult r = screen("Acme Trading", SubjectType.ORGANISATION);

        assertThat(r.matches()).isEmpty();
        assertThat(r.datasetVersionId()).isEqualTo(v2);
    }

    @Test
    void noActiveDatasetIsAnErrorNotAnEmptyResult() {
        jdbc.update("DELETE FROM sanctions_dataset_version");

        assertThatThrownBy(() -> screen("Ivan Petrov", SubjectType.PERSON))
                .isInstanceOf(SanctionsDataUnavailableException.class);
    }

    @Test
    void importStoresTheMatcherColumns() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name_normalised, name_phonetic, tokens FROM sanctions_name WHERE name_original = 'Иван Петров'");

        assertThat(row.get("name_normalised")).isEqualTo("ivan petrov");
        assertThat((String) row.get("name_phonetic")).isNotBlank();
        assertThat(textArray(row.get("tokens"))).containsExactly("ivan", "petrov");
        assertThat(jdbc.queryForObject(
                        "SELECT normaliser_version FROM sanctions_dataset_version WHERE id = ?",
                        String.class,
                        versionId))
                .isEqualTo(NameNormaliser.VERSION);
    }

    @Test
    void staleVersionsAreReindexedInPlace() {
        // as if imported before M3 (or by an older normaliser)
        jdbc.update("UPDATE sanctions_name SET name_normalised = NULL, name_phonetic = NULL, tokens = NULL");
        jdbc.update("UPDATE sanctions_dataset_version SET normaliser_version = NULL");

        assertThat(indexer.reindexStaleVersions()).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM sanctions_name WHERE name_normalised IS NULL OR tokens IS NULL"
                                + " OR name_phonetic IS NULL",
                        Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT normaliser_version FROM sanctions_dataset_version WHERE id = ?",
                        String.class,
                        versionId))
                .isEqualTo(NameNormaliser.VERSION);
        assertThat(indexer.reindexStaleVersions()).isZero();
        assertThat(topId(screen("Ivan Petrof", SubjectType.PERSON))).isEqualTo("NK-ivan");
    }

    // ---------- validation ----------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "!!! ..."})
    void namesWithNothingToScreenAreRejected(String name) {
        assertThatThrownBy(() -> screen(name, SubjectType.PERSON))
                .isInstanceOf(InvalidScreeningRequestException.class)
                .hasMessageContaining("name");
    }

    @Test
    void aNullNameIsRejected() {
        assertThatThrownBy(() -> screening.screen(new ScreeningRequest(null, SubjectType.PERSON, null, null)))
                .isInstanceOf(InvalidScreeningRequestException.class);
    }

    @Test
    void anInvalidBirthDateIsRejected() {
        assertThatThrownBy(() -> screening.screen(new ScreeningRequest("Ivan Petrov", null, "1971-13", null)))
                .isInstanceOf(InvalidScreeningRequestException.class)
                .hasMessageContaining("birthDate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Russia", "R", "R1", "RUS"})
    void aCountryMustBeAnIsoAlpha2Code(String country) {
        assertThatThrownBy(() -> screening.screen(new ScreeningRequest("Ivan Petrov", null, null, country)))
                .isInstanceOf(InvalidScreeningRequestException.class)
                .hasMessageContaining("country");
    }

    // ---------- helpers ----------

    private ImportResult importFixture(String version, String fixture) {
        OpenSanctionsStubs.publish(wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), version, fixture);
        ImportResult result = imports.importLatest();
        assertThat(result.outcome()).as(result.message()).isEqualTo(ImportOutcome.COMPLETED);
        return result;
    }

    private ScreeningResult screen(String name, SubjectType type) {
        return screening.screen(new ScreeningRequest(name, type, null, null));
    }

    private static String topId(ScreeningResult r) {
        assertThat(r.matches()).as("matches for " + r.query()).isNotEmpty();
        return r.matches().get(0).sanctionsEntityId();
    }

    private static List<String> textArray(Object sqlArray) {
        try {
            return Arrays.asList((String[]) ((Array) sqlArray).getArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
