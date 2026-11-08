package com.cypherz01.kyb.audit;

import java.util.List;
import java.util.UUID;

/** Append-only storage of snapshots and events (spec §6): insert and read, nothing else. */
public class AuditRepository {

    public void insertSnapshots(UUID checkId, List<SourceSnapshot> snapshots) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public void append(UUID checkId, AuditEvent event) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public List<SourceSnapshot> findSnapshots(UUID checkId) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /** @return the check's events, in the order they were appended */
    public List<AuditEvent> findEvents(UUID checkId) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
