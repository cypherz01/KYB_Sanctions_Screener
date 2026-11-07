package com.cypherz01.kyb.scoring;

/**
 * Applies the spec §9 rules to a check's inputs: points-based, weights from {@code
 * scoring-rules.yml}, no ML, deterministic. Every factor says what fired and why, and every rule
 * that looked but did not fire for a notable reason leaves a note.
 *
 * <p>Two rules need more than the table in spec §9:
 *
 * <ul>
 *   <li>NO_PSC_DECLARED fires only if the company is not exempt from keeping a PSC register
 *       (listed companies legitimately declare none).
 *   <li>FCA_CLONE_OR_UNAUTHORISED_MATCH does not fire when the company is itself the authorised
 *       firm (matched by Companies House number). Clone entries carry the name of the firm being
 *       impersonated, so for the genuine firm they are a note, not a risk.
 * </ul>
 */
public class RiskScorer {

    public RiskScorer(ScoringProperties properties, double possibleThreshold, double strongThreshold) {}

    public ScoreResult score(ScoringInputs inputs) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
