package com.cypherz01.kyb.fca.model;

/** Whether the regulatory status could be established, and how fresh it is (spec §5.2). */
public enum FcaAvailability {
    /** Live or within-TTL cached data. */
    AVAILABLE,
    /** The Register was unavailable; an expired cached result was used. */
    STALE,
    /** The Register was unavailable and nothing was cached: the M5 FCA_UNAVAILABLE factor. */
    UNAVAILABLE
}
