package com.cypherz01.kyb.monitoring;

import com.cypherz01.kyb.monitoring.AlertChange.MatchKey;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Decides whether a new check is worth an alert. Pure, so the rule can be tested on its own. */
public final class AlertRules {

    private AlertRules() {}

    /**
     * @param previous the previous check's stored response
     * @param current the new check's response
     * @return the change, or empty if neither the decision nor the set of matches changed
     */
    public static Optional<AlertChange> compare(JsonNode previous, JsonNode current) {
        String before = previous.path("decision").asText();
        String after = current.path("decision").asText();
        Set<MatchKey> old = matches(previous);
        Set<MatchKey> now = matches(current);

        List<MatchKey> added = new ArrayList<>(now);
        added.removeAll(old);
        List<MatchKey> removed = new ArrayList<>(old);
        removed.removeAll(now);

        if (added.isEmpty() && removed.isEmpty() && before.equals(after)) {
            return Optional.empty();
        }
        AlertChange.Type type = !added.isEmpty()
                ? AlertChange.Type.MATCHES_CHANGED
                : !removed.isEmpty() ? AlertChange.Type.MATCH_CLEARED : AlertChange.Type.DECISION_CHANGED;
        return Optional.of(new AlertChange(type, before, after, List.copyOf(added), List.copyOf(removed)));
    }

    /** @return the entity ids a check response matched */
    public static Set<String> matchedEntities(JsonNode check) {
        Set<String> ids = new LinkedHashSet<>();
        for (MatchKey key : matches(check)) {
            ids.add(key.sanctionsEntityId());
        }
        return ids;
    }

    /** Every match on the check, not only each subject's best one. */
    private static Set<MatchKey> matches(JsonNode check) {
        Set<MatchKey> keys = new LinkedHashSet<>();
        for (JsonNode subject : check.path("subjects")) {
            for (JsonNode match : subject.path("matches")) {
                keys.add(new MatchKey(
                        subject.path("name").asText(),
                        match.path("sanctionsEntityId").asText(),
                        match.path("band").asText()));
            }
        }
        return keys;
    }
}
