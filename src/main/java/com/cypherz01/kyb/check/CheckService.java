package com.cypherz01.kyb.check;

import com.cypherz01.kyb.audit.AuditEvent;
import com.cypherz01.kyb.audit.AuditEventType;
import com.cypherz01.kyb.audit.AuditRepository;
import com.cypherz01.kyb.cache.Sourced;
import com.cypherz01.kyb.companieshouse.CompaniesHouseClient;
import com.cypherz01.kyb.companieshouse.CompanyNumber;
import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscExemptions;
import com.cypherz01.kyb.fca.FcaRegisterClient;
import com.cypherz01.kyb.fca.model.FcaAssessment;
import com.cypherz01.kyb.matching.SanctionsDataUnavailableException;
import com.cypherz01.kyb.sanctions.DatasetVersion;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a KYB check (spec §4 CheckOrchestrator). It fetches the Companies House profile, officers,
 * PSCs and exemptions, pins the active sanctions dataset version, assesses the FCA Register, has
 * {@link CheckEvaluator} screen and score, and stores the result with what it rested on. The check,
 * its snapshots and its events are written in one transaction, so a stored result always has its
 * evidence, and a check that fails part-way leaves no record.
 */
public class CheckService {

    private static final Logger log = LoggerFactory.getLogger(CheckService.class);

    private final CompaniesHouseClient companiesHouse;
    private final FcaRegisterClient fca;
    private final SanctionsRepository datasets;
    private final CheckEvaluator evaluator;
    private final EvidenceSnapshots snapshots;
    private final CheckRepository checks;
    private final AuditRepository audit;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;

    public CheckService(
            CompaniesHouseClient companiesHouse,
            FcaRegisterClient fca,
            SanctionsRepository datasets,
            CheckEvaluator evaluator,
            EvidenceSnapshots snapshots,
            CheckRepository checks,
            AuditRepository audit,
            TransactionTemplate tx,
            ObjectMapper json,
            Clock clock,
            MeterRegistry meters) {
        this.companiesHouse = companiesHouse;
        this.fca = fca;
        this.datasets = datasets;
        this.evaluator = evaluator;
        this.snapshots = snapshots;
        this.checks = checks;
        this.audit = audit;
        this.tx = tx;
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
        FcaAssessment regulatory = fca.assess(company.name(), number, CheckEvidence.postcodeOf(company));
        CheckEvidence evidence = new CheckEvidence(profile, officers, pscs, exemptions, regulatory);
        Instant gatheredAt = clock.instant();

        UUID id = UUID.randomUUID();
        CheckResponse response = evaluator.evaluate(id, number, requestedAt, clock::instant, evidence, dataset);

        tx.executeWithoutResult(status -> {
            checks.insert(
                    id,
                    number,
                    requestedAt,
                    response.completedAt(),
                    response.decision().name(),
                    response.score(),
                    dataset.id(),
                    response.scoringConfigVersion(),
                    response.engineVersion(),
                    write(Map.of("companyNumber", number)),
                    write(response));
            audit.insertSnapshots(id, snapshots.toSnapshots(evidence));
            audit.append(id, requested(requestedAt, number));
            audit.append(id, gathered(gatheredAt, regulatory, dataset));
            audit.append(id, completed(response));
        });

        long nanos = System.nanoTime() - start;
        Timer.builder("kyb.checks")
                .description("KYB checks by decision")
                .tag("decision", response.decision().name())
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        log.info(
                "kyb check id={} company={} decision={} score={} subjects={} datasetVersion={} latencyMs={}",
                id,
                number,
                response.decision(),
                response.score(),
                response.subjects().size(),
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

    private AuditEvent requested(Instant at, String number) {
        return event(AuditEventType.CHECK_REQUESTED, at, Map.of("companyNumber", number));
    }

    private AuditEvent gathered(Instant at, FcaAssessment regulatory, DatasetVersion dataset) {
        return event(
                AuditEventType.SOURCES_GATHERED,
                at,
                Map.of(
                        "regulatoryAvailability",
                        regulatory.availability().name(),
                        "datasetVersion",
                        dataset.versionLabel()));
    }

    private AuditEvent completed(CheckResponse response) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("decision", response.decision().name());
        details.put("score", response.score());
        details.put(
                "factors", response.factors().stream().map(f -> f.code().name()).toList());
        details.put("subjectsScreened", response.subjects().size());
        return event(AuditEventType.CHECK_COMPLETED, response.completedAt(), details);
    }

    private AuditEvent event(AuditEventType type, Instant at, Map<String, ?> details) {
        return new AuditEvent(type, at, json.valueToTree(details));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Check result not serialisable", e);
        }
    }
}
