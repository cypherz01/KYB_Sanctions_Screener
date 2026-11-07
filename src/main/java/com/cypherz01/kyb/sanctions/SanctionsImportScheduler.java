package com.cypherz01.kyb.sanctions;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Triggers imports: nightly on a cron, and on startup (crash recovery, re-indexing names stored by
 * an older normaliser, then a first load if empty).
 */
public class SanctionsImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(SanctionsImportScheduler.class);

    private final SanctionsImportService service;
    private final SanctionsNameIndexer indexer;
    private final SanctionsProperties properties;
    private final Executor executor;

    public SanctionsImportScheduler(
            SanctionsImportService service,
            SanctionsNameIndexer indexer,
            SanctionsProperties properties,
            Executor executor) {
        this.service = service;
        this.indexer = indexer;
        this.properties = properties;
        this.executor = executor;
    }

    @Scheduled(cron = "${kyb.sanctions.cron}", zone = "${kyb.sanctions.zone}")
    public void nightly() {
        try {
            service.importLatest(); // outcome is logged and recorded by the service
        } catch (ImportAlreadyRunningException e) {
            log.info("nightly sanctions import skipped: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        service.recoverInterruptedImports();
        // Background: a full dataset takes a while and must not hold up readiness.
        executor.execute(() -> {
            try {
                indexer.reindexStaleVersions();
            } catch (RuntimeException e) {
                log.error("re-indexing sanctions names failed; will retry on next start", e);
            }
        });
        if (properties.importOnStartupIfEmpty() && service.status().active() == null) {
            try {
                long versionId = service.startAsync();
                log.info("no active sanctions dataset; started initial import version={}", versionId);
            } catch (ImportAlreadyRunningException e) {
                log.info("initial sanctions import not started: {}", e.getMessage());
            }
        }
    }
}
