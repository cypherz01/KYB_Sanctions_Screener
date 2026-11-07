package com.cypherz01.kyb.check;

import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.check.CheckResponse.Regulatory;
import com.cypherz01.kyb.check.CheckResponse.ScreenedSubject;
import com.cypherz01.kyb.check.CheckResponse.SourceRef;
import com.cypherz01.kyb.check.CheckResponse.Thresholds;
import com.cypherz01.kyb.companieshouse.CompaniesHouseClient;
import com.cypherz01.kyb.companieshouse.CompanyNumber;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import com.cypherz01.kyb.fca.FcaRegisterClient;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.matching.MatchSubject;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.NormalisedName;
import com.cypherz01.kyb.matching.SanctionsDataUnavailableException;
import com.cypherz01.kyb.matching.ScreeningMatch;
import com.cypherz01.kyb.matching.ScreeningResult;
import com.cypherz01.kyb.matching.ScreeningService;
import com.cypherz01.kyb.sanctions.DatasetVersion;
import com.cypherz01.kyb.sanctions.SanctionsAttribution;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import com.cypherz01.kyb.scoring.RiskScorer;
import com.cypherz01.kyb.scoring.ScoreResult;
import com.cypherz01.kyb.scoring.ScoringInputs;
import com.cypherz01.kyb.scoring.SourceInfo;
import com.cypherz01.kyb.scoring.SubjectScreening;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a KYB check (spec §4 CheckOrchestrator). It fetches the Companies House profile, officers,
 * PSCs and exemptions, pins the active sanctions dataset version, assesses the FCA Register,
 * screens every subject against that one version, scores, stores the result append-only, and
 * returns it.
 */
public class CheckService {

    private static final Logger log = LoggerFactory.getLogger(CheckService.class);

    private final CompaniesHouseClient companiesHouse;
    private final FcaRegisterClient fca;
    private final SanctionsRepository datasets;
    private final ScreeningService screening;
    private final NameNormaliser normaliser;
    private final SubjectExtractor extractor;
    private final RiskScorer scorer;
    private final CheckRepository checks;
    private final ChecksProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;

    public CheckService(
            CompaniesHouseClient companiesHouse,
            FcaRegisterClient fca,
            SanctionsRepository datasets,
            ScreeningService screening,
            NameNormaliser normaliser,
            SubjectExtractor extractor,
            RiskScorer scorer,
            CheckRepository checks,
            ChecksProperties props,
            ObjectMapper json,
            Clock clock,
            MeterRegistry meters) {
        this.companiesHouse = companiesHouse;
        this.fca = fca;
        this.datasets = datasets;
        this.screening = screening;
        this.normaliser = normaliser;
        this.extractor = extractor;
        this.scorer = scorer;
        this.checks = checks;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @throws com.cypherz01.kyb.companieshouse.InvalidCompanyNumberException 400
     * @throws com.cypherz01.kyb.companieshouse.CompanyNotFoundException 404
     * @throws com.cypherz01.kyb.companieshouse.CompaniesHouseUnavailableException 503 (nothing cached)
     * @throws com.cypherz01.kyb.matching.SanctionsDataUnavailableException 503 (no dataset)
     */
    public CheckResponse run(String companyNumber) {
        long start = System.nanoTime();
        Instant requestedAt = clock.instant();
        String number = CompanyNumber.parse(companyNumber).value();

        Sourced<CompanyProfile> profile = companiesHouse.getProfile(number);
        Sourced<List<Officer>> officers = companiesHouse.getOfficers(number);
        Sourced<List<Psc>> pscs = companiesHouse.getPscs(number);
        Sourced<PscExemptions> exemptions = companiesHouse.getExemptions(number);

        // One version for every subject of this check, even if an import swaps it mid-check.
        DatasetVersion dataset = datasets.findActive()
                .orElseThrow(() -> new SanctionsDataUnavailableException(
                        "No sanctions dataset has been imported yet, so a check cannot be completed"));

        CompanyProfile company = profile.value();
        String postcode = company.registeredOffice() == null
                ? null
                : company.registeredOffice().postalCode();
        FcaAssessment regulatory = fca.assess(company.name(), number, postcode);

        SubjectExtraction extraction = extractor.extract(company, officers.value(), pscs.value());
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
            screenings.add(new SubjectScreening(roles, s.publishedName(), birthMonth, best));
            screened.add(new ScreenedSubject(roles, s.publishedName(), s.type().name(), birthMonth, r.matches()));
        }

        SourceInfo chSource = combine(profile, officers, pscs, exemptions);
        ScoreResult score = scorer.score(new ScoringInputs(
                company,
                officers.value(),
                pscs.value(),
                exemptions.value(),
                chSource,
                regulatory,
                screenings,
                dataset.versionLabel(),
                LocalDate.ofInstant(requestedAt, ZoneOffset.UTC)));
        notes.addAll(score.notes());

        UUID id = UUID.randomUUID();
        String engineVersion = props.engineVersion() + "+normaliser." + NameNormaliser.VERSION;
        Instant completedAt = clock.instant();
        CheckResponse response = new CheckResponse(
                id,
                number,
                company.name(),
                score.decision(),
                score.score(),
                new Thresholds(score.reviewThreshold(), score.failThreshold()),
                score.factors(),
                List.copyOf(notes),
                List.copyOf(screened),
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
                engineVersion,
                score.scoringConfigVersion(),
                requestedAt,
                completedAt);

        checks.insert(
                id,
                number,
                requestedAt,
                completedAt,
                score.decision().name(),
                score.score(),
                dataset.id(),
                score.scoringConfigVersion(),
                engineVersion,
                write(Map.of("companyNumber", number)),
                write(response));

        long nanos = System.nanoTime() - start;
        Timer.builder("kyb.checks")
                .description("KYB checks by decision")
                .tag("decision", score.decision().name())
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        log.info(
                "kyb check id={} company={} decision={} score={} subjects={} datasetVersion={} latencyMs={}",
                id,
                number,
                score.decision(),
                score.score(),
                screened.size(),
                dataset.versionLabel(),
                TimeUnit.NANOSECONDS.toMillis(nanos));
        return response;
    }

    /**
     * @return the stored response, exactly as it was returned when the check ran
     * @throws CheckNotFoundException unknown id
     */
    public JsonNode find(UUID checkId) {
        String stored = checks.findResponse(checkId).orElseThrow(() -> new CheckNotFoundException(checkId));
        try {
            return json.readTree(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored check " + checkId + " is not valid JSON", e);
        }
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

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Check result not serialisable", e);
        }
    }
}
