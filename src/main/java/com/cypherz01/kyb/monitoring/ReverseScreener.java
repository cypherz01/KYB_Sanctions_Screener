package com.cypherz01.kyb.monitoring;

import java.util.Set;

/**
 * Screens only what changed against the monitored subjects, so the work scales with the size of the
 * change rather than the number of subscriptions (ADR-0005).
 */
public class ReverseScreener {

    /**
     * @param datasetVersionId the version the added and changed entities are read from
     * @return every monitored subject that matches an added or changed entity
     */
    public Set<ReverseHit> screen(DatasetDelta delta, long datasetVersionId) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
