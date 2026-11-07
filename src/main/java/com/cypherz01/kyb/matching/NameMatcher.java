package com.cypherz01.kyb.matching;

/**
 * Stage 2 of matching (spec §8.3): scores one candidate against the subject in Java. It combines
 * token-sorted Jaro-Winkler, token overlap, phonetic agreement and per-token fuzzy alignment with
 * the configured weights, then adjusts for birth date and country agreement. A schema the subject
 * type cannot match is excluded outright. Deterministic and side-effect free, so a stored check
 * can be re-scored exactly (M6).
 */
public class NameMatcher {

    public NameMatcher(MatchingProperties properties) {}

    public MatchScore score(MatchSubject subject, MatchCandidate candidate) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
