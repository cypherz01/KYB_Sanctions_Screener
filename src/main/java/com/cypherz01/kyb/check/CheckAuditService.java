package com.cypherz01.kyb.check;

import java.util.UUID;

/** Reads a check's audit trail and replays it (spec §11). */
public class CheckAuditService {

    /** @throws CheckNotFoundException unknown id */
    public AuditTrail trail(UUID checkId) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Re-runs normalisation, matching and scoring from the stored snapshots and dataset version. It
     * makes no external call, reads no cache and changes nothing but the audit trail.
     *
     * @throws CheckNotFoundException unknown id
     * @throws CheckNotReplayableException the dataset version was pruned, or there are no snapshots
     */
    public ReplayResult replay(UUID checkId) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
