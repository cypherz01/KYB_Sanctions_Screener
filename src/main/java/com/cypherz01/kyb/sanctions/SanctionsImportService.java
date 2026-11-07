package com.cypherz01.kyb.sanctions;

/**
 * Imports the OpenSanctions dataset. It reserves the single LOADING slot, reads the index, and
 * skips if the file is unchanged. Otherwise it streams and batch-inserts screenable entities under
 * a new version, verifies the checksum, makes that version active in one step, and prunes versions
 * beyond retention. A failed import leaves the active version untouched.
 */
public class SanctionsImportService {

    /**
     * Claims the import slot by creating a LOADING version row.
     *
     * @return the new version id
     * @throws ImportAlreadyRunningException another import is LOADING
     */
    public long reserve() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Runs a reserved import to completion. Source and data problems are reported as {@link
     * ImportOutcome#FAILED} rather than thrown, so a nightly run cannot take the scheduler down.
     */
    public ImportResult run(long versionId) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /** {@link #reserve()} then {@link #run(long)}, on the calling thread. */
    public ImportResult importLatest() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Reserves synchronously, so a conflict is reported to the caller, then runs in the background.
     *
     * @return the reserved version id
     * @throws ImportAlreadyRunningException another import is LOADING
     */
    public long startAsync() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Marks LOADING versions left by a crashed process as FAILED and deletes their rows. Called on
     * startup, before any new import can begin.
     *
     * @return the number of versions recovered
     */
    public int recoverInterruptedImports() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public SanctionsStatus status() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
