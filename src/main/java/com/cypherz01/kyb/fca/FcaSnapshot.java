package com.cypherz01.kyb.fca;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * What an {@link com.cypherz01.kyb.fca.model.FcaAssessment} rested on, as stored by a check.
 *
 * @param payload {@link com.cypherz01.kyb.fca.model.FcaAssessment#payload()}
 */
public record FcaSnapshot(JsonNode payload, Instant fetchedAt, boolean fromCache, boolean stale) {}
