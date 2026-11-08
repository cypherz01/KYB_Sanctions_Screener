package com.cypherz01.kyb.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** One entry in a check's audit trail. */
public record AuditEvent(AuditEventType eventType, Instant occurredAt, JsonNode details) {}
