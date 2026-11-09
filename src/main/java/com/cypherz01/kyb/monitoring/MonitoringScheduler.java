package com.cypherz01.kyb.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the worker on a schedule. A failed pass is logged and the next one tries again. */
public class MonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final MonitoringWorker worker;

    public MonitoringScheduler(MonitoringWorker worker) {
        this.worker = worker;
    }

    @Scheduled(cron = "${kyb.monitoring.cron}")
    public void run() {
        try {
            worker.tick();
        } catch (RuntimeException e) {
            log.error("monitoring pass failed; the next one will try again", e);
        }
    }
}
