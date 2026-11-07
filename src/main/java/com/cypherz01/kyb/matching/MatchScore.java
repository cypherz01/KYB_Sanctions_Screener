package com.cypherz01.kyb.matching;

import java.util.List;

/**
 * The scored, explained comparison of a subject with one candidate.
 *
 * @param nameSimilarity name-only similarity (weighted components, extra-token penalty applied)
 * @param similarity final 0-1 similarity after birth date and country adjustments
 * @param band STRONG / POSSIBLE, or null when below the POSSIBLE threshold (discarded)
 * @param evidence human-readable reasons, most important first
 */
public record MatchScore(
        double nameSimilarity, double similarity, MatchBand band, ComponentScores components, List<String> evidence) {}
