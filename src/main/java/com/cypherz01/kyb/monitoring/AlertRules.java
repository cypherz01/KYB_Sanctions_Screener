package com.cypherz01.kyb.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** Decides whether a new check is worth an alert. Pure, so the rule can be tested on its own. */
public final class AlertRules {

    private AlertRules() {}

    /**
     * @param previous the previous check's stored response
     * @param current the new check's response
     * @return the change, or empty if neither the decision nor the set of matches changed
     */
    public static Optional<AlertChange> compare(JsonNode previous, JsonNode current) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
