package com.cypherz01.kyb.check;

import com.cypherz01.kyb.audit.AuditEvent;
import com.cypherz01.kyb.audit.AuditEventType;
import com.cypherz01.kyb.audit.AuditRepository;
import com.cypherz01.kyb.audit.JsonDiff;
import com.cypherz01.kyb.audit.JsonDiff.Difference;
import com.cypherz01.kyb.audit.SourceSnapshot;
import com.cypherz01.kyb.check.AuditTrail.Dataset;
import com.cypherz01.kyb.check.ReplayResult.Outcome;
import com.cypherz01.kyb.sanctions.DatasetStatus;
import com.cypherz01.kyb.sanctions.DatasetVersion;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads a check's audit trail and replays it (spec §11). */
public class CheckAuditService {

    private static final Logger log = LoggerFactory.getLogger(CheckAuditService.class);

    private final CheckRepository checks;
    private final AuditRepository audit;
    private final SanctionsRepository datasets;
    private final EvidenceSnapshots snapshots;
    private final CheckEvaluator evaluator;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;

    public CheckAuditService(
            CheckRepository checks,
            AuditRepository audit,
            SanctionsRepository datasets,
            EvidenceSnapshots snapshots,
            CheckEvaluator evaluator,
            ObjectMapper json,
            Clock clock,
            MeterRegistry meters) {
        this.checks = checks;
        this.audit = audit;
        this.datasets = datasets;
        this.snapshots = snapshots;
        this.evaluator = evaluator;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
    }

    /** @throws CheckNotFoundException unknown id */
    public AuditTrail trail(UUID checkId) {
        StoredCheck check = stored(checkId);
        DatasetVersion dataset = dataset(check);
        return new AuditTrail(
                check.id(),
                check.companyNumber(),
                check.decision(),
                check.score(),
                check.requestedAt(),
                check.completedAt(),
                read(check.requestJson()),
                new Dataset(
                        dataset.id(), dataset.versionLabel(), dataset.status().name()),
                check.scoringConfigVersion(),
                check.engineVersion(),
                audit.findSnapshots(checkId),
                audit.findEvents(checkId));
    }

    /**
     * Re-runs normalisation, matching and scoring from the stored snapshots and dataset version. It
     * makes no external call, reads no cache and changes nothing but the audit trail.
     *
     * @throws CheckNotFoundException unknown id
     * @throws CheckNotReplayableException the dataset version was pruned, or there are no snapshots
     */
    public ReplayResult replay(UUID checkId) {
        StoredCheck check = stored(checkId);
        DatasetVersion dataset = dataset(check);
        requireData(check, dataset);

        List<SourceSnapshot> stored = audit.findSnapshots(checkId);
        CheckEvidence evidence = snapshots
                .restore(check.companyNumber(), stored)
                .orElseThrow(() -> refuse(
                        check,
                        "Check " + checkId + " has no complete set of source snapshots, as it predates them, so it"
                                + " cannot be replayed. Its stored result remains available."));

        CheckResponse response = evaluator.evaluate(
                checkId, check.companyNumber(), check.requestedAt(), check::completedAt, evidence, dataset);

        // Pruned while screening? Then rows vanished underneath it, and "no match" would mean nothing.
        requireData(check, dataset(check));

        JsonNode replayed = read(write(response));
        List<Difference> differences = JsonDiff.diff(read(check.responseJson()), replayed);
        boolean identical = differences.isEmpty();
        Instant replayedAt = clock.instant();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("identical", identical);
        details.put("decision", response.decision().name());
        details.put("score", response.score());
        details.put("engineVersion", response.engineVersion());
        details.put("scoringConfigVersion", response.scoringConfigVersion());
        details.put("differences", differences.stream().map(Difference::path).toList());
        audit.append(checkId, new AuditEvent(AuditEventType.CHECK_REPLAYED, replayedAt, json.valueToTree(details)));

        count(identical ? "identical" : "different");
        log.info(
                "kyb check replay id={} identical={} decision={} score={} differences={}",
                checkId,
                identical,
                response.decision(),
                response.score(),
                differences.size());
        return new ReplayResult(
                checkId,
                replayedAt,
                identical,
                differences,
                new Outcome(check.decision(), check.score(), check.engineVersion(), check.scoringConfigVersion()),
                new Outcome(
                        response.decision().name(),
                        response.score(),
                        response.engineVersion(),
                        response.scoringConfigVersion()),
                dataset.versionLabel(),
                replayed);
    }

    private void requireData(StoredCheck check, DatasetVersion dataset) {
        if (dataset.status() != DatasetStatus.COMPLETED) {
            throw refuse(
                    check,
                    "Check " + check.id() + " ran against sanctions dataset version " + dataset.versionLabel()
                            + ", which is now " + dataset.status() + " and no longer has its data, so it cannot be"
                            + " replayed. Its stored result and audit trail remain available.");
        }
    }

    /** Records the refusal: an attempt that could not be honoured is part of the record too. */
    private CheckNotReplayableException refuse(StoredCheck check, String reason) {
        audit.append(
                check.id(),
                new AuditEvent(
                        AuditEventType.CHECK_REPLAY_REFUSED,
                        clock.instant(),
                        json.valueToTree(Map.of("reason", reason))));
        count("refused");
        log.info("kyb check replay id={} refused", check.id());
        return new CheckNotReplayableException(reason);
    }

    private void count(String outcome) {
        meters.counter("kyb.checks.replays", "outcome", outcome).increment();
    }

    private StoredCheck stored(UUID checkId) {
        return checks.find(checkId).orElseThrow(() -> new CheckNotFoundException(checkId));
    }

    private DatasetVersion dataset(StoredCheck check) {
        // A foreign key guarantees this row, and pruning keeps it.
        return datasets.findById(check.datasetVersionId())
                .orElseThrow(() -> new IllegalStateException("Check " + check.id() + " has no dataset version"));
    }

    private JsonNode read(String stored) {
        try {
            return json.readTree(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored check data is not valid JSON", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Replayed check not serialisable", e);
        }
    }
}
