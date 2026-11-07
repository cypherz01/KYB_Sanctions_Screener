package com.cypherz01.kyb.sanctions;

import com.cypherz01.kyb.sanctions.model.SanctionsEntityRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imports the OpenSanctions dataset. It reserves the single LOADING slot, reads the index, and
 * skips if the file is unchanged. Otherwise it streams and batch-inserts screenable entities under
 * a new version, verifies the checksum, makes that version active in one step, and prunes versions
 * beyond retention. A failed import leaves the active version untouched.
 */
public class SanctionsImportService {

    private static final Logger log = LoggerFactory.getLogger(SanctionsImportService.class);
    private static final int MALFORMED_LINES_LOGGED = 5;

    private final OpenSanctionsSource source;
    private final FtmEntityParser parser;
    private final SanctionsRepository repository;
    private final SanctionsProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Executor executor;

    public SanctionsImportService(
            OpenSanctionsSource source,
            FtmEntityParser parser,
            SanctionsRepository repository,
            SanctionsProperties properties,
            ObjectMapper json,
            Clock clock,
            MeterRegistry meters,
            Executor executor) {
        this.source = source;
        this.parser = parser;
        this.repository = repository;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
        this.executor = executor;
        // Read from the database on each scrape, so it is right whichever process ran the import.
        Gauge.builder("kyb.sanctions.active.entities", repository, r -> r.findActive()
                        .map(v -> v.entityCount() == null ? 0d : v.entityCount().doubleValue())
                        .orElse(0d))
                .description("Entities in the active sanctions dataset version")
                .register(meters);
    }

    /**
     * Claims the import slot by creating a LOADING version row.
     *
     * @return the new version id
     * @throws ImportAlreadyRunningException another import is LOADING
     */
    public long reserve() {
        return repository.insertLoading(properties.indexUrl().toString(), clock.instant());
    }

    /**
     * Runs a reserved import to completion. Source and data problems are reported as {@link
     * ImportOutcome#FAILED} rather than thrown, so a nightly run cannot take the scheduler down.
     */
    public ImportResult run(long versionId) {
        long start = System.nanoTime();
        String label = null;
        try {
            DatasetDescriptor descriptor = source.describeLatest();
            label = descriptor.versionLabel();
            repository.describe(versionId, label, descriptor.url().toString());
            Optional<DatasetVersion> active = repository.findActive();

            // Idempotency without a download: the index publishes the file's SHA-1.
            if (descriptor.sha1() != null && active.isPresent() && sameChecksum(descriptor.sha1(), active.get())) {
                return unchanged(versionId, label, descriptor.sha1(), active.get(), start);
            }

            Loaded loaded = source.open(descriptor, body -> load(versionId, body));

            if (descriptor.sha1() != null && !descriptor.sha1().equalsIgnoreCase(loaded.sha1())) {
                throw new ImportFailure("Checksum mismatch: index publishes " + descriptor.sha1()
                        + " but the downloaded file hashes to " + loaded.sha1());
            }
            // No published checksum: only now can an unchanged file be recognised.
            if (active.isPresent() && sameChecksum(loaded.sha1(), active.get())) {
                return unchanged(versionId, label, loaded.sha1(), active.get(), start);
            }

            repository.activate(versionId, loaded.sha1(), loaded.entities(), loaded.names(), clock.instant());
            int pruned = repository.prune(properties.keepVersions(), clock.instant());
            long ms = record("completed", start);
            log.info(
                    "sanctions import completed version={} label={} entities={} names={} malformedLines={}"
                            + " duplicateIds={} pruned={} durationMs={}",
                    versionId,
                    label,
                    loaded.entities(),
                    loaded.names(),
                    loaded.malformed(),
                    loaded.duplicates(),
                    pruned,
                    ms);
            return new ImportResult(
                    versionId,
                    ImportOutcome.COMPLETED,
                    label,
                    loaded.entities(),
                    loaded.names(),
                    loaded.malformed(),
                    null);
        } catch (RuntimeException e) {
            return failed(versionId, label, e, start);
        }
    }

    /** {@link #reserve()} then {@link #run(long)}, on the calling thread. */
    public ImportResult importLatest() {
        return run(reserve());
    }

    /**
     * Reserves synchronously, so a conflict is reported to the caller, then runs in the background.
     *
     * @return the reserved version id
     * @throws ImportAlreadyRunningException another import is LOADING
     */
    public long startAsync() {
        long versionId = reserve();
        try {
            executor.execute(() -> run(versionId));
        } catch (RuntimeException e) {
            // Never leave the slot held by a run that will not happen.
            repository.markFailed(versionId, "Could not start import: " + e.getMessage(), clock.instant());
            throw e;
        }
        return versionId;
    }

    /**
     * Marks LOADING versions left by a crashed process as FAILED and deletes their rows. Called on
     * startup, before any new import can begin. Assumes a single instance (local-only deployment):
     * with replicas, this would need a heartbeat to tell "crashed" from "running elsewhere".
     *
     * @return the number of versions recovered
     */
    public int recoverInterruptedImports() {
        List<Long> loading = repository.findLoadingIds();
        for (long id : loading) {
            repository.markFailed(id, "Interrupted: the process stopped before the import finished", clock.instant());
            log.warn("sanctions import version={} was interrupted; marked FAILED and its rows removed", id);
        }
        return loading.size();
    }

    public SanctionsStatus status() {
        Optional<DatasetVersion> active = repository.findActive();
        return new SanctionsStatus(
                active.orElse(null),
                repository.findLatest().orElse(null),
                repository.isLoading(),
                active.map(v -> repository.countBySchema(v.id())).orElse(Map.of()));
    }

    /** Streams the file line by line, batching inserts, and hashes exactly the bytes received. */
    private Loaded load(long versionId, InputStream body) throws IOException {
        DigestInputStream digest = new DigestInputStream(body, sha1());
        BufferedReader reader = new BufferedReader(new InputStreamReader(digest, StandardCharsets.UTF_8), 1 << 16);
        List<SanctionsEntityRecord> batch = new ArrayList<>(properties.batchSize());
        Set<String> seenIds = new HashSet<>();
        long lineNo = 0;
        long malformed = 0;
        long duplicates = 0;
        long entities = 0;
        long names = 0;

        String line;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            Optional<SanctionsEntityRecord> parsed;
            try {
                parsed = parser.parse(json.readTree(line));
            } catch (JsonProcessingException | MalformedEntityException e) {
                malformed++;
                if (malformed <= MALFORMED_LINES_LOGGED) {
                    log.warn("sanctions import version={} skipped malformed line {}", versionId, lineNo);
                }
                if (malformed > properties.maxMalformedLines()) {
                    throw new ImportFailure("Too many malformed lines: " + malformed + " (limit "
                            + properties.maxMalformedLines() + "), last at line " + lineNo);
                }
                continue;
            }
            if (parsed.isEmpty()) {
                continue;
            }
            SanctionsEntityRecord entity = parsed.get();
            if (!seenIds.add(entity.externalId())) {
                duplicates++; // would violate (version, external_id) and fail the whole batch
                continue;
            }
            batch.add(entity);
            entities++;
            names += entity.names().size();
            if (batch.size() >= properties.batchSize()) {
                repository.insertBatch(versionId, batch);
                batch.clear();
            }
        }
        repository.insertBatch(versionId, batch);
        return new Loaded(
                HexFormat.of().formatHex(digest.getMessageDigest().digest()), entities, names, malformed, duplicates);
    }

    private ImportResult unchanged(
            long versionId, String label, String checksum, DatasetVersion active, long startNanos) {
        repository.markSkipped(versionId, checksum, clock.instant());
        long ms = record("unchanged", startNanos);
        log.info(
                "sanctions import unchanged version={} label={}: same checksum as active version={}; durationMs={}",
                versionId,
                label,
                active.id(),
                ms);
        return new ImportResult(versionId, ImportOutcome.UNCHANGED, label, 0, 0, 0, null);
    }

    private ImportResult failed(long versionId, String label, RuntimeException e, long startNanos) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        try {
            repository.markFailed(versionId, message, clock.instant());
        } catch (RuntimeException cleanup) {
            // Startup recovery will retry the cleanup; the active version is untouched either way.
            log.error("sanctions import version={} could not be marked FAILED", versionId, cleanup);
        }
        long ms = record("failed", startNanos);
        if (e instanceof SanctionsSourceException || e instanceof ImportFailure) {
            log.error("sanctions import failed version={} label={} durationMs={}: {}", versionId, label, ms, message);
        } else {
            log.error("sanctions import failed version={} label={} durationMs={}", versionId, label, ms, e);
        }
        return new ImportResult(versionId, ImportOutcome.FAILED, label, 0, 0, 0, message);
    }

    private long record(String outcome, long startNanos) {
        long nanos = System.nanoTime() - startNanos;
        Timer.builder("kyb.sanctions.import")
                .description("Sanctions dataset import runs")
                .tag("outcome", outcome)
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static boolean sameChecksum(String checksum, DatasetVersion active) {
        return active.checksum() != null && active.checksum().equalsIgnoreCase(checksum);
    }

    private static MessageDigest sha1() {
        try {
            // SHA-1 because that is what OpenSanctions publishes; it checks integrity, not authenticity.
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private record Loaded(String sha1, long entities, long names, long malformed, long duplicates) {}

    /** A data problem that fails the import (as opposed to a source outage). */
    private static final class ImportFailure extends RuntimeException {
        ImportFailure(String message) {
            super(message);
        }
    }
}
