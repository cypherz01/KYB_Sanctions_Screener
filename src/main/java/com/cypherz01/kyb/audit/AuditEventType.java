package com.cypherz01.kyb.audit;

/** What can happen to a check. Events are only ever appended. */
public enum AuditEventType {
    CHECK_REQUESTED,
    SOURCES_GATHERED,
    CHECK_COMPLETED,
    CHECK_REPLAYED,
    CHECK_REPLAY_REFUSED
}
