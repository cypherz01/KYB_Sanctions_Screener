package com.cypherz01.kyb.fca.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * The regulatory picture of one company. Never an exception: an outage is a value.
 *
 * @param query the name searched for
 * @param authorisedMatch the authorised firm believed to be this company, or null
 * @param warnings matching unauthorised or clone-firm entries
 * @param evidence how the result was reached (hits considered, exclusions, fallbacks)
 * @param fetchedAt when the oldest Register response used was fetched; null if UNAVAILABLE
 * @param fromCache every response used came from cache
 * @param stale at least one expired cached response was used because the Register was down
 * @param payload the raw Register responses used ({@code search}, {@code firms.<FRN>}) for audit
 * @param unavailableReason why nothing could be established, or null
 */
public record FcaAssessment(
        FcaAvailability availability,
        String query,
        FcaFirmMatch authorisedMatch,
        List<FcaWarningMatch> warnings,
        List<String> evidence,
        Instant fetchedAt,
        boolean fromCache,
        boolean stale,
        JsonNode payload,
        String unavailableReason) {}
