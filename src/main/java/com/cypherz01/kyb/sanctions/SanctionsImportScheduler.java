package com.cypherz01.kyb.sanctions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** Triggers imports: nightly on a cron, and on startup (crash recovery, then first load if empty). */
public class SanctionsImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(SanctionsImportScheduler.class);

    private final SanctionsImportService service;
    private final SanctionsProperties properties;

    public SanctionsImportScheduler(SanctionsImportService service, SanctionsProperties properties) {
        this.service = service;
        this.properties = properties;
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
