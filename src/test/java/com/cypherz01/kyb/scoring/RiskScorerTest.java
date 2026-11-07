package com.cypherz01.kyb.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.cypherz01.kyb.companieshouse.model.Address;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import com.cypherz01.kyb.companieshouse.model.PscKind;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.fca.model.FcaAvailability;
import com.cypherz01.kyb.fca.model.FcaFirmMatch;
import com.cypherz01.kyb.fca.model.FcaMatchBasis;
import com.cypherz01.kyb.fca.model.FcaWarningMatch;
import com.cypherz01.kyb.matching.MatchBand;
import com.cypherz01.kyb.matching.ScreeningMatch;
import com.cypherz01.kyb.sanctions.model.NameKind;
import com.cypherz01.kyb.support.ShippedConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec §9 rules with the shipped scoring-rules.yml. Each test starts from a clean company. */
class RiskScorerTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 11, 7);
    static final Instant CH_FETCHED = Instant.parse("2026-11-07T11:00:00Z");
    static final Instant FCA_FETCHED = Instant.parse("2026-11-06T09:00:00Z");

    final ScoringProperties props = ShippedConfig.scoring();
    final RiskScorer scorer = new RiskScorer(props, 0.80, 0.90);

    // ---------- baseline ----------

    @Test
    void aCleanCompanyPassesWithNoFactors() {
        ScoreResult r = scorer.score(clean().build());

        assertThat(r.decision()).isEqualTo(Decision.PASS);
        assertThat(r.score()).isZero();
        assertThat(r.factors()).isEmpty();
        assertThat(r.reviewThreshold()).isEqualTo(40);
        assertThat(r.failThreshold()).isEqualTo(80);
        assertThat(r.scoringConfigVersion()).isEqualTo(props.version());
    }

    @Test
    void scoringIsDeterministic() {
        ScoringInputs in = clean().status("dissolved").pscs(List.of()).build();

        assertThat(scorer.score(in)).isEqualTo(scorer.score(in));
    }

    // ---------- Companies House rules ----------

    @ParameterizedTest
    @ValueSource(strings = {"dissolved", "liquidation", "administration", "receivership"})
    void aCompanyThatIsNotActiveScores(String status) {
        Factor f = only(scorer.score(clean().status(status).build()), FactorCode.COMPANY_NOT_ACTIVE);

        assertThat(f.points()).isEqualTo(50);
        assertThat(f.detail()).contains(status);
        assertThat(f.source().name()).isEqualTo("Companies House");
        assertThat(f.source().fetchedAt()).isEqualTo(CH_FETCHED);
    }

    @Test
    void anActiveCompanyDoesNot() {
        assertThat(codes(scorer.score(clean().status("active").build()))).doesNotContain(FactorCode.COMPANY_NOT_ACTIVE);
    }

    @Test
    void aRecentlyIncorporatedCompanyScoresAndSaysHowRecent() {
        Factor f = only(
                scorer.score(clean().incorporatedOn(AS_OF.minusDays(21)).build()),
                FactorCode.COMPANY_RECENTLY_INCORPORATED);

        assertThat(f.points()).isEqualTo(10);
        assertThat(f.detail()).contains("21 days").contains("90 days");
    }

    @Test
    void ninetyDaysIsNoLongerRecent() {
        assertThat(codes(
                        scorer.score(clean().incorporatedOn(AS_OF.minusDays(90)).build())))
                .doesNotContain(FactorCode.COMPANY_RECENTLY_INCORPORATED);
    }

    @Test
    void overdueAccountsOrConfirmationStatementScoreOnce() {
        Factor accounts = only(scorer.score(clean().accountsOverdue(true).build()), FactorCode.ACCOUNTS_OVERDUE);
        Factor cs = only(scorer.score(clean().csOverdue(true).build()), FactorCode.ACCOUNTS_OVERDUE);
        ScoreResult both =
                scorer.score(clean().accountsOverdue(true).csOverdue(true).build());

        assertThat(accounts.points()).isEqualTo(10);
        assertThat(accounts.detail()).containsIgnoringCase("accounts");
        assertThat(cs.detail()).containsIgnoringCase("confirmation statement");
        assertThat(both.factors())
                .filteredOn(f -> f.code() == FactorCode.ACCOUNTS_OVERDUE)
                .hasSize(1);
        assertThat(both.score()).isEqualTo(10);
    }

    @Test
    void noPscsAndNoExemptionScores() {
        Factor f = only(scorer.score(clean().pscs(List.of()).build()), FactorCode.NO_PSC_DECLARED);

        assertThat(f.points()).isEqualTo(15);
    }

    @Test
    void onlyCeasedPscsCountsAsNone() {
        Psc ceased = individualPsc("Mrs Sarah Jones", LocalDate.of(2020, 1, 1));

        assertThat(codes(scorer.score(clean().pscs(List.of(ceased)).build()))).contains(FactorCode.NO_PSC_DECLARED);
    }

    @Test
    void anExemptCompanyWithNoPscsDoesNotScoreButIsNoted() {
        ScoreResult r = scorer.score(clean().pscs(List.of())
                .exemptions(new PscExemptions(List.of("psc-exempt-as-trading-on-uk-regulated-market")))
                .build());

        assertThat(codes(r)).doesNotContain(FactorCode.NO_PSC_DECLARED);
        assertThat(r.notes()).anyMatch(n -> n.contains("psc-exempt-as-trading-on-uk-regulated-market"));
    }

    @Test
    void aSuperSecurePscIsADeclaredPsc() {
        Psc secure =
                new Psc(null, PscKind.SUPER_SECURE, null, null, null, LocalDate.of(2020, 1, 1), null, null, List.of());

        assertThat(codes(scorer.score(clean().pscs(List.of(secure)).build())))
                .doesNotContain(FactorCode.NO_PSC_DECLARED);
    }

    @Test
    void aCorporatePscRegisteredOffshoreScoresAndNamesTheCountry() {
        Factor f = only(
                scorer.score(clean().pscs(List.of(corporatePsc("OFFSHORE HOLDINGS S.A.", "Panama", null)))
                        .build()),
                FactorCode.CORPORATE_PSC_OFFSHORE);

        assertThat(f.points()).isEqualTo(10);
        assertThat(f.detail()).contains("OFFSHORE HOLDINGS S.A.").contains("Panama");
    }

    @ParameterizedTest
    @ValueSource(strings = {"England", "United Kingdom", "SCOTLAND", "England And Wales"})
    void aUkCorporatePscDoesNotScore(String country) {
        assertThat(codes(scorer.score(clean().pscs(List.of(corporatePsc("UK HOLDINGS LTD", country, null)))
                        .build())))
                .doesNotContain(FactorCode.CORPORATE_PSC_OFFSHORE);
    }

    @Test
    void aCorporatePscWithNoKnownCountryIsNotedNotScored() {
        ScoreResult r = scorer.score(clean().pscs(List.of(corporatePsc("MYSTERY HOLDINGS", null, null)))
                .build());

        assertThat(codes(r)).doesNotContain(FactorCode.CORPORATE_PSC_OFFSHORE);
        assertThat(r.notes()).anyMatch(n -> n.contains("MYSTERY HOLDINGS"));
    }

    @Test
    void aCeasedOffshorePscDoesNotScore() {
        assertThat(codes(scorer.score(clean().pscs(List.of(
                                individualPsc("Mrs Sarah Jones", null),
                                corporatePsc("OLD OFFSHORE S.A.", "Panama", LocalDate.of(2022, 1, 1))))
                        .build())))
                .doesNotContain(FactorCode.CORPORATE_PSC_OFFSHORE);
    }

    @Test
    void threeOfficerChangesInTwelveMonthsIsHighChurn() {
        List<Officer> officers = List.of(
                director("SMITH, John", LocalDate.of(2012, 1, 1), null),
                director("BROWN, Amy", AS_OF.minusMonths(2), null),
                director("GREEN, Tom", AS_OF.minusMonths(5), null),
                director("WHITE, Ann", LocalDate.of(2015, 1, 1), AS_OF.minusMonths(1)));

        Factor f = only(scorer.score(clean().officers(officers).build()), FactorCode.HIGH_OFFICER_CHURN);

        assertThat(f.points()).isEqualTo(5);
        assertThat(f.detail()).contains("3");
    }

    @Test
    void twoChangesOrOldChangesAreNotChurn() {
        List<Officer> officers = List.of(
                director("SMITH, John", LocalDate.of(2012, 1, 1), null),
                director("BROWN, Amy", AS_OF.minusMonths(2), null),
                director("GREEN, Tom", AS_OF.minusMonths(13), null),
                director("WHITE, Ann", LocalDate.of(2015, 1, 1), AS_OF.minusMonths(1)));

        assertThat(codes(scorer.score(clean().officers(officers).build())))
                .doesNotContain(FactorCode.HIGH_OFFICER_CHURN);
    }

    // ---------- FCA rules ----------

    @Test
    void anUnavailableRegisterScoresWithTheReason() {
        Factor f = only(
                scorer.score(clean().fca(unavailableFca("FCA Register search returned HTTP 503"))
                        .build()),
                FactorCode.FCA_UNAVAILABLE);

        assertThat(f.points()).isEqualTo(10);
        assertThat(f.detail()).contains("could not be verified").contains("503");
    }

    @Test
    void aStaleRegisterResultIsUsedAndNoted() {
        FcaAssessment stale = new FcaAssessment(
                FcaAvailability.STALE, "X", null, List.of(), List.of(), FCA_FETCHED, true, true, null, null);

        ScoreResult r = scorer.score(clean().fca(stale).build());

        assertThat(codes(r)).doesNotContain(FactorCode.FCA_UNAVAILABLE);
        assertThat(r.notes()).anyMatch(n -> n.toLowerCase().contains("stale"));
    }

    @Test
    void aMatchingCloneOrUnauthorisedEntryScoresWhenTheCompanyIsNotTheAuthorisedFirm() {
        FcaWarningMatch clone = clone("Acme Payments Ltd (Clone)", "999001");

        Factor f = only(
                scorer.score(clean().fca(fca(null, List.of(clone))).build()),
                FactorCode.FCA_CLONE_OR_UNAUTHORISED_MATCH);

        assertThat(f.points()).isEqualTo(60);
        assertThat(f.detail()).contains("Acme Payments Ltd (Clone)").contains("999001");
        assertThat(f.source().name()).isEqualTo("FCA Register");
        assertThat(f.source().fetchedAt()).isEqualTo(FCA_FETCHED);
    }

    @Test
    void clonesOfTheGenuineFirmAreNotItsRiskButAreNoted() {
        FcaAssessment a = fca(
                authorised(FcaMatchBasis.COMPANIES_HOUSE_NUMBER), List.of(clone("Acme Payments (Clone)", "999001")));

        ScoreResult r = scorer.score(clean().fca(a).build());

        assertThat(codes(r)).doesNotContain(FactorCode.FCA_CLONE_OR_UNAUTHORISED_MATCH);
        assertThat(r.notes())
                .anyMatch(n -> n.contains("999001") && n.toLowerCase().contains("impersonat"));
    }

    @Test
    void clonesStillScoreWhenTheAuthorisedMatchRestsOnlyOnTheName() {
        FcaAssessment a = fca(authorised(FcaMatchBasis.NAME), List.of(clone("Acme Payments (Clone)", "999001")));

        assertThat(codes(scorer.score(clean().fca(a).build()))).contains(FactorCode.FCA_CLONE_OR_UNAUTHORISED_MATCH);
    }

    @Test
    void aRegulatedActivityWithoutAnAuthorisationScores() {
        Factor f = only(
                scorer.score(clean().sicCodes(List.of("64921")).build()), FactorCode.FCA_NOT_AUTHORISED_FOR_ACTIVITY);

        assertThat(f.points()).isEqualTo(25);
        assertThat(f.detail()).contains("64921").containsIgnoringCase("credit granting");
    }

    @Test
    void aRegulatedActivityWithAnAuthorisationDoesNot() {
        assertThat(codes(scorer.score(clean().sicCodes(List.of("64921"))
                        .fca(fca(authorised(FcaMatchBasis.COMPANIES_HOUSE_NUMBER), List.of()))
                        .build())))
                .doesNotContain(FactorCode.FCA_NOT_AUTHORISED_FOR_ACTIVITY);
    }

    @Test
    void whenTheRegisterIsUnavailableOnlyThatIsScored() {
        ScoreResult r = scorer.score(
                clean().sicCodes(List.of("64921")).fca(unavailableFca("down")).build());

        assertThat(codes(r))
                .contains(FactorCode.FCA_UNAVAILABLE)
                .doesNotContain(FactorCode.FCA_NOT_AUTHORISED_FOR_ACTIVITY);
    }

    @Test
    void anUnregulatedActivityNeedsNoAuthorisation() {
        assertThat(codes(scorer.score(clean().sicCodes(List.of("10710")).build())))
                .doesNotContain(FactorCode.FCA_NOT_AUTHORISED_FOR_ACTIVITY);
    }

    // ---------- sanctions rules ----------

    @Test
    void aStrongMatchIsAnAutomaticFailWithItsEvidence() {
        SubjectScreening petrov =
                screened(List.of("DIRECTOR", "PSC"), "PETROV, Ivan", "1971-03", match(0.97, MatchBand.STRONG));

        ScoreResult r =
                scorer.score(clean().screenings(List.of(company(), petrov)).build());

        assertThat(r.decision()).isEqualTo(Decision.FAIL);
        Factor f = only(r, FactorCode.SANCTIONS_STRONG_MATCH);
        assertThat(f.autoFail()).isTrue();
        assertThat(f.subject()).isEqualTo(new Factor.Subject(List.of("DIRECTOR", "PSC"), "PETROV, Ivan", "1971-03"));
        assertThat(f.match().sanctionsEntityId()).isEqualTo("NK-ivan");
        assertThat(f.match().matchedName()).isEqualTo("Ivan Petrov");
        assertThat(f.match().lists()).containsExactly("UK FCDO", "EU");
        assertThat(f.match().similarity()).isEqualTo(0.97);
        assertThat(f.match().band()).isEqualTo("STRONG");
        assertThat(f.match().evidence()).contains("Birth year and month match (1971-03)");
        assertThat(f.source().name()).isEqualTo("OpenSanctions");
        assertThat(f.source().datasetVersion()).isEqualTo("v-test");
    }

    @Test
    void aPossibleMatchScoresItsBaseAtThePossibleThreshold() {
        Factor f = only(
                scorer.score(clean().screenings(List.of(
                                company(),
                                screened(List.of("DIRECTOR"), "PETROF, Ivan", null, match(0.80, MatchBand.POSSIBLE))))
                        .build()),
                FactorCode.SANCTIONS_POSSIBLE_MATCH);

        assertThat(f.points()).isEqualTo(35);
        assertThat(f.autoFail()).isFalse();
    }

    @Test
    void aPossibleMatchEarnsMoreTheCloserItIsToStrong() {
        Factor f = only(
                scorer.score(clean().screenings(List.of(
                                screened(List.of("DIRECTOR"), "PETROF, Ivan", null, match(0.85, MatchBand.POSSIBLE))))
                        .build()),
                FactorCode.SANCTIONS_POSSIBLE_MATCH);

        assertThat(f.points()).isEqualTo(43); // 35 + 15 * (0.85 - 0.80) / (0.90 - 0.80) = 42.5, rounded
    }

    @Test
    void eachMatchingSubjectIsItsOwnFactor() {
        ScoreResult r = scorer.score(clean().screenings(List.of(
                        screened(List.of("DIRECTOR"), "PETROF, Ivan", null, match(0.80, MatchBand.POSSIBLE)),
                        screened(List.of("PSC"), "Mr Ivan Petrof", null, match(0.80, MatchBand.POSSIBLE))))
                .build());

        assertThat(r.factors())
                .filteredOn(f -> f.code() == FactorCode.SANCTIONS_POSSIBLE_MATCH)
                .hasSize(2);
        assertThat(r.score()).isEqualTo(70);
        assertThat(r.decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void subjectsWithoutMatchesScoreNothing() {
        assertThat(scorer.score(clean().screenings(List.of(
                                        company(), screened(List.of("DIRECTOR"), "JONES, Sarah", "1980-05", null)))
                                .build())
                        .factors())
                .isEmpty();
    }

    // ---------- decision ----------

    @Test
    void theReviewThresholdIsInclusive() {
        ScoreResult r =
                scorer.score(clean().pscs(List.of()).sicCodes(List.of("64921")).build()); // 15 + 25

        assertThat(r.score()).isEqualTo(40);
        assertThat(r.decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void justBelowReviewPasses() {
        ScoreResult r = scorer.score(
                clean().sicCodes(List.of("64921")).accountsOverdue(true).build()); // 25 + 10

        assertThat(r.score()).isEqualTo(35);
        assertThat(r.decision()).isEqualTo(Decision.PASS);
    }

    @Test
    void reachingTheFailThresholdFails() {
        ScoreResult r = scorer.score(clean().status("dissolved")
                .pscs(List.of())
                .accountsOverdue(true)
                .incorporatedOn(AS_OF.minusDays(10))
                .build()); // 50+15+10+10

        assertThat(r.score()).isEqualTo(85);
        assertThat(r.decision()).isEqualTo(Decision.FAIL);
    }

    @Test
    void factorsAreListedInRuleOrder() {
        ScoreResult r = scorer.score(clean().status("dissolved")
                .pscs(List.of())
                .accountsOverdue(true)
                .build());

        assertThat(codes(r))
                .containsExactly(
                        FactorCode.COMPANY_NOT_ACTIVE, FactorCode.ACCOUNTS_OVERDUE, FactorCode.NO_PSC_DECLARED);
    }

    @Test
    void pointsComeFromConfig() {
        Map<FactorCode, Integer> points = new EnumMap<>(props.points());
        points.put(FactorCode.COMPANY_NOT_ACTIVE, 99);
        ScoringProperties custom = new ScoringProperties(
                "custom",
                props.thresholds(),
                points,
                props.sanctionsPossibleScale(),
                props.recentlyIncorporatedDays(),
                props.officerChurn(),
                props.inactiveStatuses(),
                props.ukCountries(),
                props.regulatedSicCodes());

        ScoreResult r = new RiskScorer(custom, 0.80, 0.90)
                .score(clean().status("dissolved").build());

        assertThat(r.score()).isEqualTo(99);
        assertThat(r.decision()).isEqualTo(Decision.FAIL);
        assertThat(r.scoringConfigVersion()).isEqualTo("custom");
    }

    // ---------- builders ----------

    static Inputs clean() {
        return new Inputs();
    }

    static final class Inputs {
        String status = "active";
        LocalDate incorporatedOn = LocalDate.of(2010, 5, 1);
        List<String> sicCodes = List.of("10710");
        boolean accountsOverdue;
        boolean csOverdue;
        List<Officer> officers = new ArrayList<>(List.of(director("JONES, Sarah", LocalDate.of(2010, 5, 1), null)));
        List<Psc> pscs = new ArrayList<>(List.of(individualPsc("Mrs Sarah Jones", null)));
        PscExemptions exemptions = PscExemptions.NONE;
        FcaAssessment fca = RiskScorerTest.fca(null, List.of());
        List<SubjectScreening> screenings =
                List.of(company(), screened(List.of("DIRECTOR", "PSC"), "JONES, Sarah", "1980-05", null));

        Inputs status(String s) {
            status = s;
            return this;
        }

        Inputs incorporatedOn(LocalDate d) {
            incorporatedOn = d;
            return this;
        }

        Inputs sicCodes(List<String> s) {
            sicCodes = s;
            return this;
        }

        Inputs accountsOverdue(boolean b) {
            accountsOverdue = b;
            return this;
        }

        Inputs csOverdue(boolean b) {
            csOverdue = b;
            return this;
        }

        Inputs officers(List<Officer> o) {
            officers = o;
            return this;
        }

        Inputs pscs(List<Psc> p) {
            pscs = p;
            return this;
        }

        Inputs exemptions(PscExemptions e) {
            exemptions = e;
            return this;
        }

        Inputs fca(FcaAssessment a) {
            fca = a;
            return this;
        }

        Inputs screenings(List<SubjectScreening> s) {
            screenings = s;
            return this;
        }

        ScoringInputs build() {
            CompanyProfile profile = new CompanyProfile(
                    "07654321",
                    "BRIGHT BAKERY LTD",
                    status,
                    "ltd",
                    incorporatedOn,
                    new Address("1 High St", null, "Leeds", null, "LS1 1AA", "England"),
                    sicCodes,
                    accountsOverdue,
                    csOverdue,
                    false);
            return new ScoringInputs(
                    profile,
                    officers,
                    pscs,
                    exemptions,
                    new SourceInfo(CH_FETCHED, false, false),
                    fca,
                    screenings,
                    "v-test",
                    AS_OF);
        }
    }

    static Officer director(String name, LocalDate appointed, LocalDate resigned) {
        return new Officer(
                name, "director", false, appointed, resigned, "British", "United Kingdom", YearMonth.of(1980, 5));
    }

    static Psc individualPsc(String name, LocalDate ceased) {
        return new Psc(
                name,
                PscKind.INDIVIDUAL,
                "British",
                "United Kingdom",
                YearMonth.of(1980, 5),
                LocalDate.of(2016, 6, 30),
                ceased,
                null,
                List.of("ownership-of-shares-75-to-100-percent"));
    }

    static Psc corporatePsc(String name, String country, LocalDate ceased) {
        return new Psc(
                name,
                PscKind.CORPORATE,
                null,
                null,
                null,
                LocalDate.of(2018, 1, 1),
                ceased,
                country,
                List.of("ownership-of-shares-50-to-75-percent"));
    }

    static FcaAssessment fca(FcaFirmMatch match, List<FcaWarningMatch> warnings) {
        return new FcaAssessment(
                FcaAvailability.AVAILABLE,
                "BRIGHT BAKERY LTD",
                match,
                warnings,
                List.of(),
                FCA_FETCHED,
                false,
                false,
                null,
                null);
    }

    static FcaAssessment unavailableFca(String reason) {
        return new FcaAssessment(
                FcaAvailability.UNAVAILABLE,
                "BRIGHT BAKERY LTD",
                null,
                List.of(),
                List.of(),
                null,
                false,
                false,
                null,
                reason);
    }

    static FcaFirmMatch authorised(FcaMatchBasis basis) {
        return new FcaFirmMatch("123456", "Bright Bakery Ltd", "Authorised", basis, 1.0, "LS1 1AA", "07654321");
    }

    static FcaWarningMatch clone(String name, String frn) {
        return new FcaWarningMatch(frn, name, "Unauthorised", 0.98, List.of("Register status 'Unauthorised'"));
    }

    static SubjectScreening company() {
        return screened(List.of("COMPANY"), "BRIGHT BAKERY LTD", null, null);
    }

    static SubjectScreening screened(List<String> roles, String name, String birthMonth, ScreeningMatch match) {
        return new SubjectScreening(roles, name, birthMonth, match);
    }

    static ScreeningMatch match(double similarity, MatchBand band) {
        return new ScreeningMatch(
                "NK-ivan",
                "Person",
                "Ivan Petrov",
                "Ivan Petrov",
                NameKind.PRIMARY,
                similarity,
                band,
                List.of("UK FCDO", "EU"),
                List.of("GB-RUS"),
                List.of("1971-03-14"),
                List.of("ru"),
                List.of("Birth year and month match (1971-03)"));
    }

    // ---------- assertions ----------

    static List<FactorCode> codes(ScoreResult r) {
        return r.factors().stream().map(Factor::code).toList();
    }

    static Factor only(ScoreResult r, FactorCode code) {
        List<Factor> matching =
                r.factors().stream().filter(f -> f.code() == code).toList();
        assertThat(matching)
                .as("factors " + codes(r) + " should contain exactly one " + code)
                .hasSize(1);
        return matching.get(0);
    }
}
