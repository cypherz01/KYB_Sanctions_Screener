package com.cypherz01.kyb.monitoring;

/** The background work of monitoring, safe to run on several replicas at once. */
public class MonitoringWorker {

    /** One full pass: sanctions runs, due periodic re-checks, jobs, then webhooks. */
    public void tick() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /** Runs up to {@code jobs-per-tick} due re-checks. */
    public void processJobs() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
