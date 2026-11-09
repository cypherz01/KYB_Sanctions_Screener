package com.cypherz01.kyb.monitoring;

import com.cypherz01.kyb.check.CheckOrigin;
import com.cypherz01.kyb.check.CheckResponse;
import com.cypherz01.kyb.check.CheckService;
import com.cypherz01.kyb.sanctions.DatasetStatus;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The background work of monitoring, safe to run on several replicas at once: each stage claims its work
 * with FOR UPDATE SKIP LOCKED, so two workers take different work or none.
 */
public class MonitoringWorker {

    private static final Logger log = LoggerFactory.getLogger(MonitoringWorker.class);

    static final String SANCTIONS_UPDATE = "SANCTIONS_UPDATE";
    static final String FULL_RESCREEN = "FULL_RESCREEN";

    private final MonitoringRepository repository;
    private final SanctionsRepository datasets;
    private final DatasetDeltas deltas;
    private final ReverseScreener reverse;
    private final CheckService checks;
    private final WebhookDispatcher webhooks;
    private final MonitoringProperties props;
    private final String fingerprint;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** @param fingerprint identifies the engine and matcher config, a change to which forces a full re-screen */
    public MonitoringWorker(
            MonitoringRepository repository,
            SanctionsRepository datasets,
            DatasetDeltas deltas,
            ReverseScreener reverse,
            CheckService checks,
            WebhookDispatcher webhooks,
            MonitoringProperties props,
            String fingerprint,
            TransactionTemplate tx,
            Clock clock) {
        this.repository = repository;
        this.datasets = datasets;
        this.deltas = deltas;
        this.reverse = reverse;
        this.checks = checks;
        this.webhooks = webhooks;
        this.props = props;
        this.fingerprint = fingerprint;
        this.tx = tx;
        this.clock = clock;
    }

    /** One full pass: sanctions runs, due periodic re-checks, jobs, then webhooks. */
    public void tick() {
        stage("sanctions runs", this::processRuns);
        stage("periodic re-checks", () -> {
            Instant now = clock.instant();
            tx.executeWithoutResult(s -> repository.enqueueDuePeriodic(now, now.plus(props.recheckInterval())));
        });
        stage("re-checks", this::processJobs);
        stage("webhooks", webhooks::deliverDue);
    }

    /** A failure in one stage must not stop the others: webhooks should still go out if screening breaks. */
    private static void stage(String name, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.error("monitoring: {} failed; the next pass will try again", name, e);
        }
    }

    /** Turns activated datasets, and any change of engine or matcher, into re-check jobs. */
    private void processRuns() {
        tx.executeWithoutResult(status -> repository.lockState().ifPresent(state -> {
            Instant now = clock.instant();
            boolean matcherChanged =
                    state.fingerprint() != null && !state.fingerprint().equals(fingerprint);
            Long base = state.lastDatasetVersionId();
            List<MonitoringRepository.Run> runs = repository.pendingRuns();

            if (runs.isEmpty() && matcherChanged) {
                log.info("monitoring: the engine or matcher changed; re-screening every subscription");
                repository.enqueueAllActive(FULL_RESCREEN, now);
            }
            for (MonitoringRepository.Run run : runs) {
                if (matcherChanged || !comparable(base, run.datasetVersionId())) {
                    repository.enqueueAllActive(FULL_RESCREEN, now);
                } else {
                    enqueueAffected(base, run.datasetVersionId(), now);
                }
                repository.finishRun(run.id(), now);
                base = run.datasetVersionId();
            }
            if (!runs.isEmpty() || !Objects.equals(state.fingerprint(), fingerprint)) {
                repository.saveState(base, fingerprint);
            }
        }));
    }

    /** A pruned version has lost its rows, so there is nothing left to compare against. */
    private boolean comparable(Long base, long versionId) {
        return base != null && hasData(base) && hasData(versionId);
    }

    private boolean hasData(long versionId) {
        return datasets.findById(versionId)
                .map(v -> v.status() == DatasetStatus.COMPLETED)
                .orElse(false);
    }

    private void enqueueAffected(long base, long versionId, Instant now) {
        DatasetDelta delta = deltas.between(base, versionId);
        Set<UUID> affected = new HashSet<>();
        for (ReverseHit hit : reverse.screen(delta, versionId)) {
            affected.add(hit.subscriptionId());
        }
        // Whoever matched an entity that went away or changed needs looking at too.
        Set<String> removedOrChanged = new HashSet<>(delta.removed());
        removedOrChanged.addAll(delta.changed());
        affected.addAll(repository.subscriptionsMatching(removedOrChanged));
        repository.enqueue(affected, SANCTIONS_UPDATE, now);
        log.info(
                "monitoring: dataset version={} added={} removed={} changed={} subscriptionsAffected={}",
                versionId,
                delta.added().size(),
                delta.removed().size(),
                delta.changed().size(),
                affected.size());
    }

    /** Runs up to {@code jobs-per-tick} due re-checks. */
    public void processJobs() {
        for (int i = 0; i < props.jobsPerTick(); i++) {
            AtomicReference<MonitoringRepository.Job> claimed = new AtomicReference<>();
            try {
                if (!Boolean.TRUE.equals(tx.execute(status -> runOneJob(claimed)))) {
                    return;
                }
            } catch (RuntimeException e) {
                MonitoringRepository.Job job = claimed.get();
                if (job == null) {
                    throw e; // nothing was claimed, so this is not about any one job
                }
                // Rolled back, so this is recorded afresh. The job moves back, so it cannot block the queue.
                log.warn(
                        "monitoring: re-check of company={} failed and stays queued: {}",
                        job.companyNumber(),
                        e.getMessage());
                tx.executeWithoutResult(status -> repository.retryJob(job.id(), retryAt(), e.getMessage()));
            }
        }
    }

    /**
     * The claim is held for the whole check, and the check joins this transaction, so the check and the
     * end of its job commit together. Any failure rolls back both, which keeps the job for later: an
     * outage says nothing about the company.
     *
     * @return whether there was a job to run
     */
    private boolean runOneJob(AtomicReference<MonitoringRepository.Job> claimed) {
        return repository
                .claimDueJob(clock.instant())
                .map(job -> {
                    claimed.set(job);
                    CheckResponse response = checks.run(
                            job.companyNumber(),
                            new CheckOrigin(job.trigger(), job.subscriptionId(), job.latestCheckId()));
                    if (restedOnStaleData(response)) {
                        // Kept and flagged, as any check is, but it does not count as having looked again.
                        repository.retryJob(job.id(), retryAt(), "Companies House served stale data");
                    } else {
                        repository.completeJob(job.id());
                    }
                    return true;
                })
                .orElse(false);
    }

    private Instant retryAt() {
        return clock.instant().plus(props.jobRetryDelay());
    }

    private static boolean restedOnStaleData(CheckResponse response) {
        return response.sources().stream()
                .anyMatch(s -> "Companies House".equals(s.name()) && Boolean.TRUE.equals(s.stale()));
    }
}
