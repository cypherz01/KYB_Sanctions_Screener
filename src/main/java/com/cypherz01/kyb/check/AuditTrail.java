package com.cypherz01.kyb.check;

import com.cypherz01.kyb.audit.AuditEvent;
import com.cypherz01.kyb.audit.SourceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything recorded about a check (spec §7, §11): what was asked, what each source returned, what
 * it was run against, and what has happened to it since.
 */
public record AuditTrail(
        UUID checkId,
        String companyNumber,
        String decision,
        int score,
        Instant requestedAt,
        Instant completedAt,
        JsonNode request,
        Dataset datasetVersion,
        String scoringConfigVersion,
        String engineVersion,
        List<SourceSnapshot> snapshots,
        List<AuditEvent> events) {

    /** @param status {@code PRUNED} means the data has gone, so the check can no longer be replayed */
    public record Dataset(long id, String label, String status) {}
}
