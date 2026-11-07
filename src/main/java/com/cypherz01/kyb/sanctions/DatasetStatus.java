package com.cypherz01.kyb.sanctions;

/** Lifecycle of a {@code sanctions_dataset_version} row. */
public enum DatasetStatus {
    /** Import in progress. Its rows are invisible to screening. At most one exists. */
    LOADING,
    /** Fully loaded. Exactly one completed version is active at a time. */
    COMPLETED,
    /** Upstream file identical to the active version (same checksum), so nothing was loaded. */
    SKIPPED,
    /** Import failed or was interrupted. Its rows have been removed. */
    FAILED,
    /** Fell outside the retention window. Its rows have been removed, the record is kept. */
    PRUNED
}
