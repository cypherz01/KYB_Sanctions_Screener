package com.cypherz01.kyb.sanctions;

/**
 * Result of one import run.
 *
 * @param versionId the {@code sanctions_dataset_version} row recording this run
 * @param malformedLines lines skipped because they were not valid entity JSON
 * @param message failure reason, or {@code null}
 */
public record ImportResult(
        long versionId,
        ImportOutcome outcome,
        String versionLabel,
        long entityCount,
        long nameCount,
        long malformedLines,
        String message) {}
