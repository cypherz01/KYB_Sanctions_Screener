package com.cypherz01.kyb.sanctions;

public enum ImportOutcome {
    /** A new version was loaded and is now active. */
    COMPLETED,
    /** Upstream had not changed since the active version, so nothing was loaded (idempotency). */
    UNCHANGED,
    /** The import failed. The previously active version is still active. */
    FAILED
}
