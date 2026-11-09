package com.cypherz01.kyb.scoring;

import com.cypherz01.kyb.ownership.EffectiveInterest;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * One contributing factor of a decision, with its evidence (spec §7 example response).
 *
 * @param autoFail true if this factor alone makes the decision FAIL
 * @param subject who the factor is about (sanctions factors), or null
 * @param match the sanctions match behind the factor, or null
 * @param source where the underlying data came from, and how fresh it was, or null
 * @param ownership what an upstream subject effectively holds in the company, or null for anyone else
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Factor(
        FactorCode code,
        int points,
        boolean autoFail,
        String detail,
        Subject subject,
        Match match,
        Source source,
        EffectiveInterest ownership) {

    /**
     * @param roles e.g. {@code DIRECTOR}, {@code PSC} (one person can hold several)
     * @param birthMonth {@code yyyy-MM} as published by Companies House, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subject(List<String> roles, String name, String birthMonth) {}

    public record Match(
            String sanctionsEntityId,
            String matchedName,
            List<String> lists,
            double similarity,
            String band,
            List<String> evidence) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String name, Instant fetchedAt, Boolean stale, String datasetVersion) {}
}
