package com.cypherz01.kyb.scoring;

import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscKind;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.fca.model.FcaAvailability;
import com.cypherz01.kyb.fca.model.FcaMatchBasis;
import com.cypherz01.kyb.fca.model.FcaWarningMatch;
import com.cypherz01.kyb.matching.MatchBand;
import com.cypherz01.kyb.matching.ScreeningMatch;
import com.cypherz01.kyb.ownership.ChainStop;
import com.cypherz01.kyb.ownership.EffectiveInterest;
import com.cypherz01.kyb.ownership.StopReason;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies the spec §9 rules to a check's inputs: points-based, weights from {@code
 * scoring-rules.yml}, no ML, deterministic. Every factor says what fired and why, and every rule
 * that looked but did not fire for a notable reason leaves a note.
 *
 * <p>Two rules need more than the table in spec §9:
 *
 * <ul>
 *   <li>NO_PSC_DECLARED fires only if the company is not exempt from keeping a PSC register
 *       (listed companies legitimately declare none).
 *   <li>FCA_CLONE_OR_UNAUTHORISED_MATCH does not fire when the company is itself the authorised
 *       firm (matched by Companies House number). Clone entries carry the name of the firm being
 *       impersonated, so for the genuine firm they are a note, not a risk.
 * </ul>
 */
public class RiskScorer {

    private static final String CH = "Companies House";
    private static final String FCA = "FCA Register";
    private static final String OPENSANCTIONS = "OpenSanctions";

    private final ScoringProperties props;
    private final double possible;
    private final double strong;
    private final Set<String> inactive;
    private final Set<String> uk;

    public RiskScorer(ScoringProperties properties, double possibleThreshold, double strongThreshold) {
        this.props = properties;
        this.possible = possibleThreshold;
        this.strong = strongThreshold;
        this.inactive = lower(properties.inactiveStatuses());
        this.uk = lower(properties.ukCountries());
    }

    public ScoreResult score(ScoringInputs in) {
        List<Factor> factors = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        companyNotActive(in, factors);
        recentlyIncorporated(in, factors);
        accountsOverdue(in, factors);
        noPscDeclared(in, factors, notes);
        corporatePscOffshore(in, factors, notes);
        ownershipStops(in, StopReason.Category.OPAQUE, FactorCode.OPAQUE_OWNERSHIP, factors);
        ownershipStops(in, StopReason.Category.LIMIT, FactorCode.OWNERSHIP_LIMIT_REACHED, factors);
        ownershipStops(in, StopReason.Category.CIRCULAR, FactorCode.CIRCULAR_OWNERSHIP, factors);
        cleanOwnershipEnds(in, notes);
        officerChurn(in, factors);
        fcaClone(in, factors, notes);
        fcaNotAuthorised(in, factors);
        fcaUnavailable(in, factors, notes);
        sanctions(in, MatchBand.POSSIBLE, factors);
        sanctions(in, MatchBand.STRONG, factors);

        int score = factors.stream().mapToInt(Factor::points).sum();
        boolean autoFail = factors.stream().anyMatch(Factor::autoFail);
        Decision decision = autoFail || score >= props.thresholds().fail()
                ? Decision.FAIL
                : score >= props.thresholds().review() ? Decision.REVIEW : Decision.PASS;
        return new ScoreResult(
                decision,
                score,
                props.thresholds().review(),
                props.thresholds().fail(),
                List.copyOf(factors),
                List.copyOf(notes),
                props.version());
    }

    // ---------- Companies House ----------

    private void companyNotActive(ScoringInputs in, List<Factor> factors) {
        String status = in.profile().status();
        if (status != null && inactive.contains(status.toLowerCase(Locale.ROOT))) {
            factors.add(ch(in, FactorCode.COMPANY_NOT_ACTIVE, "Company status is '" + status + "'"));
        }
    }

    private void recentlyIncorporated(ScoringInputs in, List<Factor> factors) {
        LocalDate incorporated = in.profile().incorporatedOn();
        if (incorporated == null) {
            return;
        }
        long days = ChronoUnit.DAYS.between(incorporated, in.asOf());
        if (days >= 0 && days < props.recentlyIncorporatedDays()) {
            factors.add(ch(
                    in,
                    FactorCode.COMPANY_RECENTLY_INCORPORATED,
                    "Incorporated " + days + " days ago (threshold: " + props.recentlyIncorporatedDays() + " days)"));
        }
    }

    private void accountsOverdue(ScoringInputs in, List<Factor> factors) {
        CompanyProfile p = in.profile();
        List<String> overdue = new ArrayList<>();
        if (p.accountsOverdue()) {
            overdue.add("accounts");
        }
        if (p.confirmationStatementOverdue()) {
            overdue.add("confirmation statement");
        }
        if (!overdue.isEmpty()) {
            factors.add(ch(
                    in, FactorCode.ACCOUNTS_OVERDUE, "Overdue at Companies House: " + String.join(" and ", overdue)));
        }
    }

    private void noPscDeclared(ScoringInputs in, List<Factor> factors, List<String> notes) {
        boolean anyCurrent = in.pscs().stream().anyMatch(Psc::isCurrent);
        if (anyCurrent) {
            return;
        }
        if (in.exemptions() != null && in.exemptions().exempt()) {
            notes.add("No PSCs declared, but the company is exempt from keeping a PSC register ("
                    + String.join(", ", in.exemptions().current()) + ")");
            return;
        }
        factors.add(
                ch(in, FactorCode.NO_PSC_DECLARED, "No current person with significant control and no PSC exemption"));
    }

    private void corporatePscOffshore(ScoringInputs in, List<Factor> factors, List<String> notes) {
        List<String> offshore = new ArrayList<>();
        for (Psc psc : in.pscs()) {
            if (!psc.isCurrent() || (psc.kind() != PscKind.CORPORATE && psc.kind() != PscKind.LEGAL_PERSON)) {
                continue;
            }
            String country = psc.registrationCountry();
            if (country == null || country.isBlank()) {
                notes.add("Corporate PSC '" + psc.name() + "' has no published country of registration");
            } else if (!uk.contains(country.trim().toLowerCase(Locale.ROOT))) {
                offshore.add(psc.name() + " (" + country.trim() + ")");
            }
        }
        if (!offshore.isEmpty()) {
            factors.add(ch(
                    in,
                    FactorCode.CORPORATE_PSC_OFFSHORE,
                    "Corporate PSC registered outside the UK: " + String.join(", ", offshore)));
        }
    }

    /** One factor per kind of ending, naming every chain it covers, as a gap is one risk however many. */
    private void ownershipStops(ScoringInputs in, StopReason.Category category, FactorCode code, List<Factor> factors) {
        List<String> details = in.ownershipStops().stream()
                .filter(s -> s.reason().category() == category)
                .map(ChainStop::detail)
                .toList();
        if (!details.isEmpty()) {
            factors.add(ch(in, code, "Ownership cannot be followed to its end: " + String.join("; ", details)));
        }
    }

    private static void cleanOwnershipEnds(ScoringInputs in, List<String> notes) {
        in.ownershipStops().stream()
                .filter(s -> s.reason().category() == StopReason.Category.CLEAN)
                .map(ChainStop::detail)
                .forEach(notes::add);
    }

    private void officerChurn(ScoringInputs in, List<Factor> factors) {
        LocalDate since = in.asOf().minusDays(props.officerChurn().windowDays());
        int changes = 0;
        for (Officer o : in.officers()) {
            if (within(o.appointedOn(), since, in.asOf())) {
                changes++;
            }
            if (within(o.resignedOn(), since, in.asOf())) {
                changes++;
            }
        }
        if (changes >= props.officerChurn().minChanges()) {
            factors.add(ch(
                    in,
                    FactorCode.HIGH_OFFICER_CHURN,
                    changes + " officer appointments/resignations in the last "
                            + props.officerChurn().windowDays() + " days (threshold: "
                            + props.officerChurn().minChanges() + ")"));
        }
    }

    // ---------- FCA ----------

    private void fcaClone(ScoringInputs in, List<Factor> factors, List<String> notes) {
        FcaAssessment fca = in.fca();
        if (fca == null || fca.warnings().isEmpty()) {
            return;
        }
        String entries = fca.warnings().stream()
                .sorted(Comparator.comparingDouble(FcaWarningMatch::similarity).reversed())
                .map(w -> "'" + w.name() + "' (FRN " + w.frn() + ", " + w.status() + ")")
                .collect(Collectors.joining(", "));
        if (fca.authorisedMatch() != null && fca.authorisedMatch().basis() == FcaMatchBasis.COMPANIES_HOUSE_NUMBER) {
            notes.add("The company is the authorised firm FRN "
                    + fca.authorisedMatch().frn() + "; the Register lists entries impersonating it: " + entries);
            return;
        }
        factors.add(fcaFactor(
                in,
                FactorCode.FCA_CLONE_OR_UNAUTHORISED_MATCH,
                "Name matches an FCA unauthorised/clone firm entry: " + entries));
    }

    private void fcaNotAuthorised(ScoringInputs in, List<Factor> factors) {
        FcaAssessment fca = in.fca();
        if (fca == null || fca.availability() == FcaAvailability.UNAVAILABLE || fca.authorisedMatch() != null) {
            return;
        }
        List<String> regulated = in.profile().sicCodes().stream()
                .filter(props.regulatedSicCodes()::containsKey)
                .map(c -> c + " (" + props.regulatedSicCodes().get(c) + ")")
                .toList();
        if (!regulated.isEmpty()) {
            factors.add(fcaFactor(
                    in,
                    FactorCode.FCA_NOT_AUTHORISED_FOR_ACTIVITY,
                    "SIC code " + String.join(", ", regulated) + " but no FCA authorisation found"));
        }
    }

    private void fcaUnavailable(ScoringInputs in, List<Factor> factors, List<String> notes) {
        FcaAssessment fca = in.fca();
        if (fca == null) {
            return;
        }
        if (fca.availability() == FcaAvailability.UNAVAILABLE) {
            factors.add(new Factor(
                    FactorCode.FCA_UNAVAILABLE,
                    points(FactorCode.FCA_UNAVAILABLE),
                    false,
                    "Regulatory status could not be verified (" + fca.unavailableReason() + ")",
                    null,
                    null,
                    new Factor.Source(FCA, null, null, null),
                    null));
        } else if (fca.availability() == FcaAvailability.STALE) {
            notes.add("The FCA Register was unavailable; a stale cached result fetched at " + fca.fetchedAt()
                    + " was used");
        }
    }

    // ---------- sanctions ----------

    private void sanctions(ScoringInputs in, MatchBand band, List<Factor> factors) {
        for (SubjectScreening s : in.screenings()) {
            ScreeningMatch m = s.bestMatch();
            if (m == null || m.band() != band) {
                continue;
            }
            boolean isStrong = band == MatchBand.STRONG;
            EffectiveInterest upstream = s.upstream();
            boolean autoFail = isStrong && (upstream == null || holdsHalfOrControl(upstream));
            FactorCode code = isStrong ? FactorCode.SANCTIONS_STRONG_MATCH : FactorCode.SANCTIONS_POSSIBLE_MATCH;
            int points =
                    isStrong ? (autoFail ? points(code) : props.thresholds().review()) : possiblePoints(m.similarity());
            String detail = String.format(
                            Locale.ROOT,
                            "%s '%s' %s matches sanctioned %s '%s' (similarity %.2f)",
                            String.join("/", s.roles()),
                            s.name(),
                            isStrong ? "strongly" : "possibly",
                            m.schema(),
                            m.matchedName(),
                            m.similarity())
                    + (upstream == null ? "" : "; " + describe(upstream));
            factors.add(new Factor(
                    code,
                    points,
                    autoFail,
                    detail,
                    new Factor.Subject(s.roles(), s.name(), s.birthMonth()),
                    new Factor.Match(
                            m.sanctionsEntityId(),
                            m.matchedName(),
                            m.lists(),
                            m.similarity(),
                            m.band().name(),
                            m.evidence()),
                    new Factor.Source(OPENSANCTIONS, null, null, in.datasetVersion()),
                    upstream));
        }
    }

    /** OFAC's 50% rule and the UK's "owned or controlled" test, applied to what the bands can prove. */
    private boolean holdsHalfOrControl(EffectiveInterest interest) {
        return interest.control()
                || interest.lowerPercent() >= props.ownership().failAtPercent();
    }

    private static String describe(EffectiveInterest interest) {
        if (interest.control()) {
            return "holds control of the company through the ownership chain";
        }
        return "effective ownership " + percent(interest.lowerPercent()) + "% to " + percent(interest.upperPercent())
                + "%";
    }

    /** Exact, because every published band is a multiple of a quarter: 37.5, not 37.500000. */
    private static String percent(double value) {
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }

    /** Base points, plus up to {@code sanctions-possible-scale} by closeness to STRONG. */
    private int possiblePoints(double similarity) {
        double position = strong > possible ? (similarity - possible) / (strong - possible) : 0;
        position = Math.max(0, Math.min(1, position));
        double points = points(FactorCode.SANCTIONS_POSSIBLE_MATCH) + props.sanctionsPossibleScale() * position;
        // Similarities are 3-decimal values: absorb binary floating-point error (0.85-0.80 = 0.0499...)
        // so a half point always rounds up.
        return (int) Math.round(points + 1e-9);
    }

    // ---------- helpers ----------

    private Factor ch(ScoringInputs in, FactorCode code, String detail) {
        SourceInfo s = in.companiesHouse();
        return new Factor(
                code,
                points(code),
                false,
                detail,
                null,
                null,
                new Factor.Source(CH, s == null ? null : s.fetchedAt(), s == null ? null : s.stale(), null),
                null);
    }

    private Factor fcaFactor(ScoringInputs in, FactorCode code, String detail) {
        FcaAssessment fca = in.fca();
        return new Factor(
                code,
                points(code),
                false,
                detail,
                null,
                null,
                new Factor.Source(FCA, fca.fetchedAt(), fca.stale(), null),
                null);
    }

    private int points(FactorCode code) {
        Integer p = props.points().get(code);
        if (p == null) {
            throw new IllegalStateException("scoring-rules.yml has no points for " + code);
        }
        return p;
    }

    private static boolean within(LocalDate date, LocalDate since, LocalDate asOf) {
        return date != null && date.isAfter(since) && !date.isAfter(asOf);
    }

    private static Set<String> lower(List<String> values) {
        return values.stream().map(v -> v.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
