package com.cypherz01.kyb.monitoring;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.cache.SourceCache;
import com.cypherz01.kyb.matching.MatchSubject;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.PartialDate;
import com.cypherz01.kyb.matching.ScreeningMatch;
import com.cypherz01.kyb.matching.ScreeningService;
import com.cypherz01.kyb.matching.SubjectType;
import com.cypherz01.kyb.sanctions.DatasetVersion;
import com.cypherz01.kyb.sanctions.ImportOutcome;
import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import com.cypherz01.kyb.support.ApiIntegrationTest;
import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.OpenSanctionsStubs;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ADR-0005. Screening only what changed is worth having only if it finds what screening everything
 * would have found, so the required test here compares the two directly.
 */
@ApiIntegrationTest
class ReverseScreeningIntegrationTest {

    static final String BEFORE = "sanctions/entities-screening.ftm.json";
    static final String AFTER = "monitoring/entities-after.ftm.json";
    static final String COMPANY = "07654321";

    /** Everyone the company below has, as a screen of them would see them. */
    record Watched(String published, String screened, String birthDate) {}

    static final List<Watched> WATCHED = List.of(
            new Watched("BRIGHT BAKERY LTD", "BRIGHT BAKERY LTD", null),
            new Watched("SMITH, John", "John Smith", "1980-05"),
            new Watched("JONES, Sarah", "Sarah Jones", "1985-02"),
            new Watched("OLSEN, Karl", "Karl Olsen", "1960-06"),
            new Watched("DRAGO, Ivan", "Ivan Drago", "1975-09"),
            new Watched("BANKS, Adam", "Adam Banks", "1970-01"),
            new Watched("HALL, Robert", "Robert Hall", "1990-12"));

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
    SanctionsRepository datasets;

    @Autowired
    SourceCache cache;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DatasetDeltas deltas;

    @Autowired
    ReverseScreener reverse;

    @Autowired
    ScreeningService screening;

    @Autowired
    NameNormaliser normaliser;

    long before;
    long after;

    @BeforeEach
    void reset() {
        wm.resetAll();
        cache.clearAll();
        jdbc.update("TRUNCATE kyb_check CASCADE");
        jdbc.update("DELETE FROM sanctions_dataset_version");
        before = importDataset("rev-before", BEFORE);
        after = importDataset("rev-after", AFTER);
    }

    // ---------- the delta ----------

    @Test
    void findsAddedEntities() {
        assertThat(deltas.between(before, after).added()).containsExactlyInAnyOrder("NK-olsen", "NK-oneword");
    }

    @Test
    void findsRemovedEntities() {
        assertThat(deltas.between(before, after).removed()).containsExactly("NK-drago");
    }

    @Test
    void findsAChangeInEveryFieldScreeningReads() {
        assertThat(deltas.between(before, after).changed())
                .containsExactlyInAnyOrder(
                        "NK-smith", // birth date
                        "NK-ivanov", // names
                        "NK-petrova", // nationalities
                        "NK-romashka", // countries
                        "NK-bout", // topics
                        "NK-global"); // schema
    }

    @Test
    void ignoresAChangeInAFieldScreeningDoesNotRead() {
        DatasetDelta delta = deltas.between(before, after);

        assertThat(delta.changed())
                .doesNotContain(
                        "NK-acme", // caption only
                        "NK-unlabelled"); // source lists only
    }

    @Test
    void comparesAVersionWithItselfAsNoChange() {
        DatasetDelta delta = deltas.between(after, after);

        assertThat(delta.added()).isEmpty();
        assertThat(delta.removed()).isEmpty();
        assertThat(delta.changed()).isEmpty();
    }

    // ---------- reverse screening ----------

    /** The test ADR-0005 requires: the same new matches as re-screening everyone in full. */
    @Test
    void raisesExactlyTheSameNewMatchesAsAFullForwardReScreen() {
        DatasetVersion older = datasets.findById(before).orElseThrow();
        DatasetVersion newer = datasets.findById(after).orElseThrow();
        Set<String> before = forward(older);
        Set<String> after = forward(newer);
        Set<String> forwardNew = new HashSet<>(after);
        forwardNew.removeAll(before);
        assertThat(forwardNew).as("the fixtures must exercise this").isNotEmpty();

        subscribe();
        Set<String> reverseNew = reverse.screen(deltas.between(older.id(), newer.id()), newer.id()).stream()
                .map(h -> key(h.subjectName(), h.externalId(), h.band().name()))
                .collect(Collectors.toCollection(HashSet::new));
        reverseNew.removeAll(before);

        assertThat(reverseNew).isEqualTo(forwardNew);
    }

    @Test
    void findsAnAddedEntityAndAChangedBirthDateAndAChangedName() {
        subscribe();

        Set<ReverseHit> hits = reverse.screen(deltas.between(before, after), after);

        assertThat(hits).extracting(ReverseHit::externalId).contains("NK-olsen", "NK-smith", "NK-ivanov");
    }

    /** A name is not new, but its score is: all names of a changed entity must be scored again. */
    @Test
    void aBirthDateChangeAloneIsEnoughForAHit() {
        subscribe();

        Set<ReverseHit> hits = reverse.screen(new DatasetDelta(Set.of(), Set.of(), Set.of("NK-smith")), after);

        assertThat(hits).extracting(ReverseHit::subjectName).containsExactly("SMITH, John");
    }

    /** Same orientation as forward screening, or this rule from the real Barclays run would not hold. */
    @Test
    void aOneWordListedPersonAliasIsStillDiscarded() {
        subscribe();

        Set<ReverseHit> hits = reverse.screen(new DatasetDelta(Set.of("NK-oneword"), Set.of(), Set.of()), after);

        assertThat(hits).isEmpty();
    }

    @Test
    void aPersonIsNeverComparedWithACompany() {
        subscribe();

        Set<ReverseHit> hits = reverse.screen(deltas.between(before, after), after);

        assertThat(hits).extracting(ReverseHit::subjectName).doesNotContain("BRIGHT BAKERY LTD");
    }

    @Test
    void nobodySubscribedMeansNoHits() {
        assertThat(reverse.screen(deltas.between(before, after), after)).isEmpty();
    }

    // ---------- helpers ----------

    private Set<String> forward(DatasetVersion version) {
        Set<String> found = new HashSet<>();
        for (Watched w : WATCHED) {
            SubjectType type = w.birthDate() == null ? SubjectType.ORGANISATION : SubjectType.PERSON;
            PartialDate birth = w.birthDate() == null
                    ? null
                    : PartialDate.parse(w.birthDate()).orElseThrow();
            Set<String> countries = w.birthDate() == null ? Set.of() : Set.of("gb");
            MatchSubject subject = new MatchSubject(normaliser.normalise(w.screened(), type), type, birth, countries);
            for (ScreeningMatch m : screening.screen(subject, version).matches()) {
                found.add(key(w.published(), m.sanctionsEntityId(), m.band().name()));
            }
        }
        return found;
    }

    private static String key(String subject, String entity, String band) {
        return subject + "|" + entity + "|" + band;
    }

    /** Subscribes through the API, which is what fills the index of subjects. */
    private void subscribe() {
        String base = "/company/" + COMPANY;
        String officers = WATCHED.stream()
                .filter(w -> w.birthDate() != null)
                .map(w -> {
                    String[] date = w.birthDate().split("-");
                    return """
                            {"name":"%s","officer_role":"director","appointed_on":"2015-01-01","nationality":"British",
                             "date_of_birth":{"month":%d,"year":%s}}
                            """
                            .formatted(w.published(), Integer.parseInt(date[1]), date[0]);
                })
                .collect(Collectors.joining(","));
        wm.stubFor(get(urlPathEqualTo(base)).willReturn(okJson(Fixtures.read("checks/clean-profile.json"))));
        wm.stubFor(get(urlPathEqualTo(base + "/officers"))
                .willReturn(okJson("{\"items\":[" + officers + "],\"total_results\":6}")));
        wm.stubFor(get(urlPathEqualTo(base + "/persons-with-significant-control"))
                .willReturn(okJson("{\"items\":[],\"total_results\":0}")));
        wm.stubFor(
                get(urlPathEqualTo(base + "/exemptions")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/Search")).willReturn(okJson(Fixtures.read("fca/search-empty.json"))));

        ResponseEntity<String> response =
                http.postForEntity("/api/v1/monitoring/subscriptions", Map.of("companyNumber", COMPANY), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    private long importDataset(String version, String fixture) {
        byte[] body = Fixtures.bytes(fixture);
        OpenSanctionsStubs.publish(
                wm.getRuntimeInfo().getWireMock(), wm.baseUrl(), version, body, OpenSanctionsStubs.sha1(body));
        assertThat(imports.importLatest().outcome()).isEqualTo(ImportOutcome.COMPLETED);
        return datasets.findActive().orElseThrow().id();
    }
}
