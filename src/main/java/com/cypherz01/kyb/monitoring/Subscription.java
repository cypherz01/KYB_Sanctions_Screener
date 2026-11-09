package com.cypherz01.kyb.monitoring;

import java.time.Instant;
import java.util.UUID;

/** An explicit opt-in to watch one company (ADR-0004). */
public record Subscription(
        UUID subscriptionId, String companyNumber, Instant createdAt, UUID latestCheckId, Instant nextRecheckAt) {}
