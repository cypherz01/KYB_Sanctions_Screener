package com.cypherz01.kyb.sanctions;

import java.time.Instant;

/** One {@code sanctions_dataset_version} row. Counts and finish time are null until known. */
public record DatasetVersion(
        long id,
        String sourceUrl,
        String versionLabel,
        String checksum,
        Long entityCount,
        Long nameCount,
        Instant startedAt,
        Instant finishedAt,
        DatasetStatus status,
        boolean active,
        String error) {}
