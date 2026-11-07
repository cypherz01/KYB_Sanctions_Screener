package com.cypherz01.kyb.api;

import com.cypherz01.kyb.sanctions.DatasetVersion;
import com.cypherz01.kyb.sanctions.SanctionsAttribution;
import java.util.Map;

/** Response bodies for {@code /api/v1/admin/sanctions/*} (spec §7). */
public final class SanctionsAdminDtos {

    private SanctionsAdminDtos() {}

    /** 202 body for {@code POST /import}. The import runs in the background. */
    public record ImportAccepted(long versionId, String statusUrl) {}

    /** {@code GET /status}. */
    public record StatusResponse(
            DatasetVersion active,
            DatasetVersion lastImport,
            boolean importRunning,
            Map<String, Long> activeEntitiesBySchema,
            SanctionsAttribution source) {}
}
