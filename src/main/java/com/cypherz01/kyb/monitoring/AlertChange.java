package com.cypherz01.kyb.monitoring;

import java.util.List;

/**
 * How a check's outcome differs from the one before it.
 *
 * @param added matches the new check has and the previous one did not
 * @param removed matches the previous check had and the new one does not
 */
public record AlertChange(
        Type type, String decisionBefore, String decisionAfter, List<MatchKey> added, List<MatchKey> removed) {

    public enum Type {
        MATCHES_CHANGED,
        MATCH_CLEARED,
        DECISION_CHANGED
    }

    /**
     * What identifies a match across checks. Similarity is left out, so a small score change raises no
     * alert; band is kept, so POSSIBLE becoming STRONG does.
     */
    public record MatchKey(String subject, String sanctionsEntityId, String band) {}
}
