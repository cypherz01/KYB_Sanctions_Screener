package com.cypherz01.kyb.matching;

import java.util.List;

/**
 * @param normalisedQuery the subject name after normalisation (shows what was actually compared)
 * @param datasetVersionId the sanctions dataset version screened against (for audit and replay)
 * @param candidatesConsidered names retrieved in stage 1 and scored in stage 2
 * @param matches entities at POSSIBLE or above, best first, one per entity
 */
public record ScreeningResult(
        String query,
        SubjectType type,
        String normalisedQuery,
        long datasetVersionId,
        String datasetVersionLabel,
        int candidatesConsidered,
        List<ScreeningMatch> matches) {}
