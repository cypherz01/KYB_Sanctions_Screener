package com.cypherz01.kyb.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.sanctions.model.NameKind;
import com.cypherz01.kyb.support.Fixtures;
import com.cypherz01.kyb.support.MatchingDefaults;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Spec §8.4: every fixture pair must score as expected with the shipped configuration (M3
 * acceptance). Also measures precision and recall at a range of thresholds and writes them to
 * {@code target/matching-accuracy.md}. The README numbers come from that report.
 */
class MatchingFixturesTest {

    static final NameNormaliser NORMALISER = MatchingDefaults.normaliser();
    static final NameMatcher MATCHER = MatchingDefaults.matcher();
    static final MatchingProperties PROPS = MatchingDefaults.properties();

    enum Expect {
        STRONG,
        MATCH,
        WEAK,
        NONE
    }

    record Pair(
            String id,
            Expect expect,
            SubjectType type,
            String subject,
            String subjectDob,
            String subjectCountry,
            String candidate,
            String schema,
            List<String> candidateDobs,
            List<String> candidateCountries,
            String note) {

        boolean positive() {
            return expect == Expect.STRONG || expect == Expect.MATCH;
        }
    }

    static List<Pair> pairs() {
        return Fixtures.read("matching/name-pairs.psv")
                .lines()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(MatchingFixturesTest::parse)
                .toList();
    }

    static Stream<Arguments> fixtures() {
        return pairs().stream().map(p -> Arguments.of(Named.of(p.id() + " (" + p.note() + ")", p)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void scoresAsExpected(Pair p) {
        MatchScore s = score(p);
        String why = p.subject() + " vs " + p.candidate() + " -> " + s;

        switch (p.expect()) {
            case STRONG -> assertThat(s.band()).as(why).isEqualTo(MatchBand.STRONG);
            case MATCH -> assertThat(s.band()).as(why).isNotNull();
            case WEAK -> assertThat(s.band()).as(why).isNotEqualTo(MatchBand.STRONG);
            case NONE -> assertThat(s.band()).as(why).isNull();
        }
    }

    @Test
    void fixtureSetCoversEverySpecCategory() {
        List<String> ids = pairs().stream().map(Pair::id).toList();

        for (String category : List.of(
                "exact",
                "order",
                "diacritics",
                "translit",
                "middle",
                "initials",
                "typo",
                "common",
                "dob",
                "suffix",
                "ampersand",
                "nonmatch")) {
            assertThat(ids).as(category).anyMatch(id -> id.startsWith(category + "-"));
        }
    }

    @Test
    void reportsPrecisionAndRecall() throws IOException {
        List<Pair> graded =
                pairs().stream().filter(p -> p.expect() != Expect.WEAK).toList();
        List<double[]> scored = graded.stream()
                .map(p -> new double[] {score(p).similarity(), p.positive() ? 1 : 0})
                .toList();

        StringBuilder md = new StringBuilder()
                .append("| Threshold | Precision | Recall | TP | FP | FN |\n")
                .append("|---|---|---|---|---|---|\n");
        double[] atConfigured = null;
        for (double t : new double[] {0.70, 0.75, 0.80, 0.85, 0.90, 0.95}) {
            double[] pr = precisionRecall(scored, t);
            md.append(String.format(
                    Locale.ROOT,
                    "| %.2f%s | %.3f | %.3f | %d | %d | %d |%n",
                    t,
                    Math.abs(t - PROPS.bands().possible()) < 1e-9 ? " (POSSIBLE)" : "",
                    pr[0],
                    pr[1],
                    (int) pr[2],
                    (int) pr[3],
                    (int) pr[4]));
            if (Math.abs(t - PROPS.bands().possible()) < 1e-9) {
                atConfigured = pr;
            }
        }
        String header = String.format(
                Locale.ROOT,
                "Fixture pairs: %d graded (%d should match, %d should not), %d weak-only excluded.%n%n",
                graded.size(),
                graded.stream().filter(Pair::positive).count(),
                graded.stream().filter(p -> !p.positive()).count(),
                pairs().size() - graded.size());
        Path report = Path.of("target", "matching-accuracy.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, header + md, StandardCharsets.UTF_8);
        System.out.println(header + md);

        assertThat(atConfigured)
                .as("configured POSSIBLE threshold is in the sweep")
                .isNotNull();
        assertThat(atConfigured[0]).as("precision at POSSIBLE").isEqualTo(1.0);
        assertThat(atConfigured[1]).as("recall at POSSIBLE").isEqualTo(1.0);
    }

    /** @return precision, recall, tp, fp, fn */
    private static double[] precisionRecall(List<double[]> scored, double threshold) {
        int tp = 0, fp = 0, fn = 0;
        for (double[] s : scored) {
            boolean predicted = s[0] >= threshold;
            boolean actual = s[1] == 1;
            if (predicted && actual) tp++;
            else if (predicted) fp++;
            else if (actual) fn++;
        }
        double precision = tp + fp == 0 ? 1.0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 1.0 : (double) tp / (tp + fn);
        return new double[] {precision, recall, tp, fp, fn};
    }

    private static MatchScore score(Pair p) {
        MatchSubject subject = new MatchSubject(
                NORMALISER.normalise(p.subject(), p.type()),
                p.type(),
                p.subjectDob().isEmpty()
                        ? null
                        : PartialDate.parse(p.subjectDob()).orElseThrow(),
                p.subjectCountry().isEmpty()
                        ? Set.of()
                        : Set.of(p.subjectCountry().toLowerCase(Locale.ROOT)));
        boolean person = p.schema().equals("Person");
        SubjectType candidateType = person ? SubjectType.PERSON : SubjectType.ORGANISATION;
        if (p.schema().equals("LegalEntity")) {
            candidateType = SubjectType.ANY;
        }
        MatchCandidate candidate = new MatchCandidate(
                1L,
                1L,
                "NK-" + p.id(),
                p.schema(),
                p.candidate(),
                p.candidate(),
                NameKind.PRIMARY,
                NORMALISER.normalise(p.candidate(), candidateType),
                p.candidateDobs(),
                person ? List.of() : p.candidateCountries(),
                person ? p.candidateCountries() : List.of(),
                List.of("gb_fcdo_sanctions"),
                List.of(),
                List.of("sanction"));
        return MATCHER.score(subject, candidate);
    }

    private static Pair parse(String line) {
        String[] f = line.split("\\|", -1);
        if (f.length != 11) {
            throw new IllegalArgumentException("Expected 11 fields: " + line);
        }
        return new Pair(
                f[0],
                Expect.valueOf(f[1]),
                SubjectType.valueOf(f[2]),
                f[3],
                f[4],
                f[5],
                f[6],
                f[7],
                list(f[8]),
                list(f[9]),
                f[10]);
    }

    private static List<String> list(String cell) {
        return cell.isEmpty() ? List.of() : Arrays.asList(cell.split(";"));
    }
}
