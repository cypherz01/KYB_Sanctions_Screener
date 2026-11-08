package com.cypherz01.kyb.check;

import com.cypherz01.kyb.audit.JsonDiff.Difference;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The outcome of re-running a check from its snapshots and dataset version.
 *
 * @param identical the whole result matches the stored one, not just the decision and score
 * @param differences where it does not. A changed engine or scoring version shows up here too, as
 *     replay runs today's code and rules
 * @param datasetVersion the sanctions dataset version replayed against, the one the check used
 * @param result the replayed result, in the shape of the stored check
 */
public record ReplayResult(
        UUID checkId,
        Instant replayedAt,
        boolean identical,
        List<Difference> differences,
        Outcome original,
        Outcome replayed,
        String datasetVersion,
        JsonNode result) {

    public record Outcome(String decision, int score, String engineVersion, String scoringConfigVersion) {}
}
