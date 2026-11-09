package com.cypherz01.kyb.check;

import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.check.CheckResponse.Regulatory;
import com.cypherz01.kyb.check.CheckResponse.ScreenedSubject;
import com.cypherz01.kyb.check.CheckResponse.SourceRef;
import com.cypherz01.kyb.check.CheckResponse.Thresholds;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.matching.MatchSubject;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.NormalisedName;
import com.cypherz01.kyb.matching.ScreeningMatch;
import com.cypherz01.kyb.matching.ScreeningResult;
import com.cypherz01.kyb.matching.ScreeningService;
import com.cypherz01.kyb.sanctions.DatasetVersion;
import com.cypherz01.kyb.sanctions.SanctionsAttribution;
import com.cypherz01.kyb.scoring.RiskScorer;
import com.cypherz01.kyb.scoring.ScoreResult;
import com.cypherz01.kyb.scoring.ScoringInputs;
import com.cypherz01.kyb.scoring.SourceInfo;
import com.cypherz01.kyb.scoring.SubjectScreening;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Turns evidence into a result: decides who is screened, screens them against one dataset version,
 * scores, and assembles the response. A live check and a replay both come through here, which is
 * what makes a replay a real re-run of the logic rather than a second implementation of it.
 *
 * <p>The only inputs are the arguments and the versioned data they point at. It reads no clock
 * (dates are judged as of {@code requestedAt}) and calls no external source.
 */
public class CheckEvaluator {

    private final ScreeningService screening;
    private final NameNormaliser normaliser;
    private final SubjectExtractor extractor;
    private final RiskScorer scorer;
    private final ChecksProperties props;

    public CheckEvaluator(
            ScreeningService screening,
            NameNormaliser normaliser,
            SubjectExtractor extractor,
            RiskScorer scorer,
            ChecksProperties props) {
        this.screening = screening;
        this.normaliser = normaliser;
        this.extractor = extractor;
        this.scorer = scorer;
        this.props = props;
    }

    /** @param completedAt read once the work is done: the current time live, the stored one in a replay */
    public CheckResponse evaluate(
            UUID id,
            String companyNumber,
            Instant requestedAt,
            Supplier<Instant> completedAt,
            CheckEvidence evidence,
            DatasetVersion dataset) {
        CompanyProfile company = evidence.profile().value();
        FcaAssessment regulatory = evidence.regulatory();

        SubjectExtraction extraction = extractor.extract(
                company, evidence.officers().value(), evidence.pscs().value());
        List<String> notes = new ArrayList<>(extraction.notes());
        List<SubjectScreening> screenings = new ArrayList<>();
        List<ScreenedSubject> screened = new ArrayList<>();
        for (Subject s : extraction.subjects()) {
            NormalisedName name = normaliser.normalise(s.screenedName(), s.type());
            if (name.tokens().isEmpty()) {
                notes.add("'" + s.publishedName() + "' has no screenable name");
                continue;
            }
            ScreeningResult r =
                    screening.screen(new MatchSubject(name, s.type(), s.birthDate(), s.countries()), dataset);
            List<String> roles = s.roles().stream().map(Enum::name).toList();
            String birthMonth = s.birthDate() == null ? null : s.birthDate().toString();
            ScreeningMatch best = r.matches().isEmpty() ? null : r.matches().get(0);
            screenings.add(new SubjectScreening(roles, s.publishedName(), birthMonth, best, null));
            screened.add(new ScreenedSubject(roles, s.publishedName(), s.type().name(), birthMonth, r.matches()));
        }

        SourceInfo chSource = combine(evidence.profile(), evidence.officers(), evidence.pscs(), evidence.exemptions());
        ScoreResult score = scorer.score(new ScoringInputs(
                company,
                evidence.officers().value(),
                evidence.pscs().value(),
                evidence.exemptions().value(),
                chSource,
                regulatory,
                screenings,
                dataset.versionLabel(),
                LocalDate.ofInstant(requestedAt, ZoneOffset.UTC),
                List.of()));
        notes.addAll(score.notes());

        return new CheckResponse(
                id,
                companyNumber,
                company.name(),
                score.decision(),
                score.score(),
                new Thresholds(score.reviewThreshold(), score.failThreshold()),
                score.factors(),
                List.copyOf(notes),
                List.copyOf(screened),
                null,
                new Regulatory(
                        regulatory.availability(),
                        regulatory.authorisedMatch(),
                        regulatory.warnings(),
                        regulatory.evidence()),
                List.of(
                        new SourceRef(
                                "Companies House",
                                chSource.fetchedAt(),
                                chSource.fromCache(),
                                chSource.stale(),
                                null,
                                null),
                        new SourceRef(
                                "FCA Register",
                                regulatory.fetchedAt(),
                                regulatory.fromCache(),
                                regulatory.stale(),
                                null,
                                null),
                        new SourceRef(
                                SanctionsAttribution.OPENSANCTIONS.name(),
                                dataset.finishedAt(),
                                null,
                                null,
                                dataset.versionLabel(),
                                SanctionsAttribution.OPENSANCTIONS.licence())),
                props.engineVersion() + "+normaliser." + NameNormaliser.VERSION,
                score.scoringConfigVersion(),
                requestedAt,
                completedAt.get());
    }

    /** Oldest fetch time, and fromCache/stale as seen by the check across the four CH calls. */
    private static SourceInfo combine(Sourced<?>... sources) {
        Instant oldest = null;
        boolean allFromCache = true;
        boolean anyStale = false;
        for (Sourced<?> s : sources) {
            oldest = oldest == null || s.fetchedAt().isBefore(oldest) ? s.fetchedAt() : oldest;
            allFromCache &= s.fromCache();
            anyStale |= s.stale();
        }
        return new SourceInfo(oldest, allFromCache, anyStale);
    }
}
