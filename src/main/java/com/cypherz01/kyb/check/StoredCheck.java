package com.cypherz01.kyb.check;

import java.time.Instant;
import java.util.UUID;

/** One {@code kyb_check} row. {@code responseJson} is the text exactly as it was returned. */
public record StoredCheck(
        UUID id,
        String companyNumber,
        Instant requestedAt,
        Instant completedAt,
        String decision,
        int score,
        long datasetVersionId,
        String scoringConfigVersion,
        String engineVersion,
        String requestJson,
        String responseJson) {}
