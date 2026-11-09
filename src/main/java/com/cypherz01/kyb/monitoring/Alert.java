package com.cypherz01.kyb.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * An immutable event: a check's outcome differed from the one before it. It has no status, because a
 * lifecycle would be the analyst case workflow, which is out of scope (ADR-0004).
 *
 * @param checkId the check that found the change, which holds the evidence
 * @param trigger why that check ran
 */
public record Alert(
        UUID alertId,
        UUID subscriptionId,
        String companyNumber,
        UUID checkId,
        UUID previousCheckId,
        String type,
        String trigger,
        String decisionBefore,
        String decisionAfter,
        JsonNode matchesAdded,
        JsonNode matchesRemoved,
        Instant occurredAt) {}
