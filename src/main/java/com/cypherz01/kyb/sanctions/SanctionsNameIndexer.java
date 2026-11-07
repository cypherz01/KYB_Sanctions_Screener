package com.cypherz01.kyb.sanctions;

/**
 * Keeps the stored matcher columns ({@code name_normalised}, {@code name_phonetic}, {@code tokens})
 * in step with {@link com.cypherz01.kyb.matching.NameNormaliser#VERSION}. Imports fill them as
 * they load. This re-indexes completed versions loaded before M3, or by an older normaliser, in
 * place, without downloading again (the unchanged-checksum skip would otherwise keep them stale
 * forever).
 */
public class SanctionsNameIndexer {

    /**
     * Re-normalises every name of each COMPLETED version whose {@code normaliser_version} differs
     * from the current one, then records the current version on it.
     *
     * @return the number of dataset versions re-indexed
     */
    public int reindexStaleVersions() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
