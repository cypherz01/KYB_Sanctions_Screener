package com.cypherz01.kyb.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * The exact data one check got from one source call, and how fresh it was.
 *
 * @param source e.g. {@code companies-house}
 * @param operation e.g. {@code profile}
 * @param fetchedAt when the source produced it; null if the source could not be reached
 * @param fromCache served from cache rather than a live call
 * @param stale served from an expired cache entry because the source was unavailable
 * @param payload the raw response, not our interpretation of it
 */
public record SourceSnapshot(
        String source, String operation, Instant fetchedAt, boolean fromCache, boolean stale, JsonNode payload) {}
