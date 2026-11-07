package com.cypherz01.kyb.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cypherz01.kyb.sanctions.model.NameKind;
import com.cypherz01.kyb.support.MatchingDefaults;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Behaviour of stage-2 scoring (spec §8.3), with the shipped weights and thresholds. */
class NameMatcherTest {

    final NameNormaliser normaliser = MatchingDefaults.normaliser();
    final NameMatcher matcher = MatchingDefaults.matcher();
    final MatchingProperties props = MatchingDefaults.properties();

    // ---------- name similarity ----------

    @Test
    void identicalNamesScoreOneAndAreStrong() {
        MatchScore s = score(person("Ivan Petrov"), candidate("Ivan Petrov", "Person"));

        assertThat(s.similarity()).isEqualTo(1.0);
        assertThat(s.band()).isEqualTo(MatchBand.STRONG);
        assertThat(s.components()).isEqualTo(new ComponentScores(1.0, 1.0, 1.0, 1.0));
        assertThat(s.evidence()).anyMatch(e -> e.toLowerCase().contains("identical"));
    }

    @Test
    void wordOrderDoesNotMatterAndIsExplained() {
        MatchScore s = score(person("PETROV, Ivan"), candidate("Ivan Petrov", "Person"));

        assertThat(s.nameSimilarity()).isEqualTo(1.0);
        assertThat(s.evidence()).anyMatch(e -> e.toLowerCase().contains("order"));
    }

    @Test
    void transliterationIsExplained() {
        MatchScore s = score(person("Иван Петров"), candidate("Ivan Petrov", "Person"));

        assertThat(s.band()).isEqualTo(MatchBand.STRONG);
        assertThat(s.evidence()).anyMatch(e -> e.toLowerCase().contains("transliteration"));
    }

    @Test
    void aSingleTypoIsAPossibleMatchNamingTheTokens() {
        MatchScore s = score(person("Ivan Petrof"), candidate("Ivan Petrov", "Person"));

        assertThat(s.band()).isNotNull();
        assertThat(s.similarity()).isLessThan(1.0);
        assertThat(s.evidence()).anyMatch(e -> e.contains("petrof") && e.contains("petrov"));
    }

    @Test
    void phoneticAgreementIsExplained() {
        MatchScore s = score(person("Mohammed Al Rashid"), candidate("Muhammad Al Rashid", "Person"));

        assertThat(s.band()).isNotNull();
        assertThat(s.components().phonetic()).isEqualTo(1.0);
        assertThat(s.evidence()).anyMatch(e -> e.toLowerCase().contains("phonetic"));
    }

    @Test
    void aMissingMiddleNameKeepsTheMatchAndIsExplained() {
        MatchScore s = score(person("Ivan Petrov"), candidate("Ivan Sergeyevich Petrov", "Person"));

        assertThat(s.band()).isNotNull();
        assertThat(s.components().tokenOverlap()).isEqualTo(1.0);
        assertThat(s.nameSimilarity()).isLessThan(1.0); // the extra token costs something
        assertThat(s.evidence()).anyMatch(e -> e.contains("sergeyevich"));
    }

    @Test
    void anInitialIsNeverAStrongMatch() {
        MatchScore s = score(person("J. Smith"), candidate("John Smith", "Person"));

        assertThat(s.band()).isNotEqualTo(MatchBand.STRONG);
        assertThat(s.evidence()).anyMatch(e -> e.toLowerCase().contains("initial"));
    }

    @Test
    void namesSharingOnlyOneTokenAreDiscarded() {
        MatchScore s = score(person("Ivan Petrov"), candidate("Ivan Drago", "Person"));

        assertThat(s.band()).isNull();
        assertThat(s.components().tokenOverlap()).isEqualTo(0.5);
    }

    @Test
    void completelyDifferentNamesHaveNoOverlap() {
        MatchScore s = score(person("Ivan Petrov"), candidate("Maria Gonzalez", "Person"));

        assertThat(s.band()).isNull();
        assertThat(s.components().tokenOverlap()).isZero();
    }

    @Test
    void evidenceAlwaysStatesTheNameSimilarityAndItsComponents() {
        MatchScore s = score(person("Ivan Petrof"), candidate("Ivan Petrov", "Person"));

        assertThat(s.evidence()).anyMatch(e -> e.startsWith("Name similarity"));
    }

    // ---------- birth date ----------

    @Test
    void anExactBirthDateBoostsMoreThanAMatchingYear() {
        MatchCandidate c = candidate("Ivan Petrof", "Person", List.of("1971-03-14"), List.of(), List.of());

        double none = score(person("Ivan Petrov"), c).similarity();
        double year = score(person("Ivan Petrov", "1971", Set.of()), c).similarity();
        double exact = score(person("Ivan Petrov", "1971-03-14", Set.of()), c).similarity();

        assertThat(year).isGreaterThan(none);
        assertThat(exact).isGreaterThan(year);
    }

    @Test
    void aMatchingBirthYearIsExplained() {
        MatchScore s = score(
                person("Ivan Petrov", "1971-03", Set.of()),
                candidate("Ivan Petrov", "Person", List.of("1971-03-14"), List.of(), List.of()));

        assertThat(s.evidence()).anyMatch(e -> e.contains("Birth") && e.contains("1971"));
    }

    @Test
    void aClearlyDifferentBirthYearStronglyReducesTheScore() {
        MatchScore s = score(
                person("Ivan Petrov", "1985-06", Set.of()),
                candidate("Ivan Petrov", "Person", List.of("1971-03-14"), List.of(), List.of()));

        assertThat(s.nameSimilarity()).isEqualTo(1.0);
        assertThat(s.similarity()).isCloseTo(1.0 - props.birthDate().mismatchPenalty(), within(1e-9));
        assertThat(s.band()).isNull();
        assertThat(s.evidence()).anyMatch(e -> e.contains("1985") && e.contains("1971"));
    }

    @Test
    void birthYearsWithinToleranceAreNeutral() {
        MatchScore s = score(
                person("Ivan Petrov", "1972", Set.of()),
                candidate("Ivan Petrof", "Person", List.of("1971"), List.of(), List.of()));

        assertThat(s.similarity()).isEqualTo(s.nameSimilarity());
    }

    @Test
    void theBestAgreeingBirthDateOfSeveralIsUsed() {
        MatchScore s = score(
                person("Ivan Petrov", "1985", Set.of()),
                candidate("Ivan Petrov", "Person", List.of("1971-03-14", "1985"), List.of(), List.of()));

        assertThat(s.similarity()).isGreaterThanOrEqualTo(s.nameSimilarity());
        assertThat(s.band()).isEqualTo(MatchBand.STRONG);
    }

    @Test
    void aBirthDateOnOneSideOnlyIsNeutral() {
        MatchScore s = score(person("Ivan Petrof", "1985", Set.of()), candidate("Ivan Petrov", "Person"));

        assertThat(s.similarity()).isEqualTo(s.nameSimilarity());
        assertThat(s.evidence()).noneMatch(e -> e.contains("Birth"));
    }

    @Test
    void unparseableListedBirthDatesAreIgnored() {
        MatchScore s = score(
                person("Ivan Petrof", "1985", Set.of()),
                candidate("Ivan Petrov", "Person", List.of("circa 1970"), List.of(), List.of()));

        assertThat(s.similarity()).isEqualTo(s.nameSimilarity());
    }

    // ---------- country ----------

    @Test
    void nationalityAgreementBoostsAndIsExplained() {
        MatchCandidate c = candidate("Ivan Petrof", "Person", List.of(), List.of(), List.of("ru"));

        MatchScore without = score(person("Ivan Petrov"), c);
        MatchScore with = score(person("Ivan Petrov", null, Set.of("ru")), c);

        assertThat(with.similarity()).isGreaterThan(without.similarity());
        assertThat(with.evidence()).anyMatch(e -> e.contains("matches (RU)"));
    }

    @Test
    void entityCountriesCountAsWellAsNationalities() {
        MatchScore s = score(
                person("Ivan Petrof", null, Set.of("ru")),
                candidate("Ivan Petrov", "Person", List.of(), List.of("ru"), List.of()));

        assertThat(s.similarity()).isGreaterThan(s.nameSimilarity());
    }

    @Test
    void nationalityDisagreementReduces() {
        MatchScore s = score(
                person("Ivan Petrov", null, Set.of("gb")),
                candidate("Ivan Petrov", "Person", List.of(), List.of(), List.of("ru")));

        assertThat(s.similarity()).isCloseTo(1.0 - props.country().mismatchPenalty(), within(1e-9));
        assertThat(s.evidence()).anyMatch(e -> e.contains("GB") && e.contains("RU"));
    }

    // ---------- entity type ----------

    @Test
    void aPersonIsNeverMatchedToACompany() {
        MatchScore s = score(person("Acme Trading"), candidate("Acme Trading", "Company"));

        assertThat(s.similarity()).isZero();
        assertThat(s.band()).isNull();
        assertThat(s.evidence()).anyMatch(e -> e.toLowerCase().contains("type"));
    }

    @Test
    void anOrganisationIsNeverMatchedToAPerson() {
        MatchScore s = score(subject("Ivan Petrov", SubjectType.ORGANISATION), candidate("Ivan Petrov", "Person"));

        assertThat(s.band()).isNull();
    }

    @Test
    void legalEntitiesAndUnknownSubjectTypesMatchAcrossTypes() {
        assertThat(score(person("Ivan Petrov"), candidate("Ivan Petrov", "LegalEntity"))
                        .band())
                .isEqualTo(MatchBand.STRONG);
        assertThat(score(subject("Acme Trading", SubjectType.ANY), candidate("Acme Trading", "Company"))
                        .band())
                .isEqualTo(MatchBand.STRONG);
    }

    // ---------- bounds and config ----------

    @Test
    void similarityNeverExceedsOne() {
        MatchScore s = score(
                person("Ivan Petrov", "1971-03-14", Set.of("ru")),
                candidate("Ivan Petrov", "Person", List.of("1971-03-14"), List.of(), List.of("ru")));

        assertThat(s.similarity()).isEqualTo(1.0);
    }

    @Test
    void bandThresholdsComeFromConfig() {
        MatchingProperties strict = new MatchingProperties(
                props.honorifics(),
                props.legalForms(),
                props.leadingLegalForms(),
                props.weights(),
                props.tokens(),
                new MatchingProperties.Bands(0.999, 0.995),
                props.birthDate(),
                props.country(),
                props.retrieval(),
                props.maxMatches(),
                props.sourceListLabels());

        MatchScore s = new NameMatcher(strict).score(person("Ivan Petrof"), candidate("Ivan Petrov", "Person"));

        assertThat(s.band()).isNull();
    }

    @Test
    void scoringIsDeterministic() {
        MatchSubject subject = person("Mohammed Al Rashid", "1965", Set.of("sy"));
        MatchCandidate c = candidate("Muhammad Al-Rashid", "Person", List.of("1965"), List.of(), List.of("sy"));

        assertThat(score(subject, c)).isEqualTo(score(subject, c));
    }

    // ---------- helpers ----------

    private MatchScore score(MatchSubject subject, MatchCandidate candidate) {
        return matcher.score(subject, candidate);
    }

    private MatchSubject person(String name) {
        return person(name, null, Set.of());
    }

    private MatchSubject person(String name, String dob, Set<String> countries) {
        return new MatchSubject(
                normaliser.normalise(name, SubjectType.PERSON),
                SubjectType.PERSON,
                dob == null ? null : PartialDate.parse(dob).orElseThrow(),
                countries);
    }

    private MatchSubject subject(String name, SubjectType type) {
        return new MatchSubject(normaliser.normalise(name, type), type, null, Set.of());
    }

    private MatchCandidate candidate(String name, String schema) {
        return candidate(name, schema, List.of(), List.of(), List.of());
    }

    private MatchCandidate candidate(
            String name, String schema, List<String> dobs, List<String> countries, List<String> nationalities) {
        SubjectType type = schema.equals("Person") ? SubjectType.PERSON : SubjectType.ORGANISATION;
        return new MatchCandidate(
                1L,
                1L,
                "NK-test",
                schema,
                name,
                name,
                NameKind.PRIMARY,
                normaliser.normalise(name, type),
                dobs,
                countries,
                nationalities,
                List.of("gb_fcdo_sanctions"),
                List.of(),
                List.of("sanction"));
    }
}
