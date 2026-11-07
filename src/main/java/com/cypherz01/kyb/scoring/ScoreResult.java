package com.cypherz01.kyb.scoring;

import java.util.List;

/**
 * @param score sum of factor points
 * @param factors contributing factors, in rule order
 * @param notes things the rules considered and deliberately did not score (e.g. "no PSCs, but
 *     exempt"), so a reviewer sees why a rule did not fire
 */
public record ScoreResult(
        Decision decision,
        int score,
        int reviewThreshold,
        int failThreshold,
        List<Factor> factors,
        List<String> notes,
        String scoringConfigVersion) {}
