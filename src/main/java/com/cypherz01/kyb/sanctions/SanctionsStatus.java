package com.cypherz01.kyb.sanctions;

import java.util.Map;

/**
 * Operational view of the sanctions data.
 *
 * @param active the version screening uses, or {@code null} if none has been imported yet
 * @param lastImport the most recent import attempt of any status, or {@code null}
 * @param activeEntitiesBySchema entity counts per FtM schema in the active version
 */
public record SanctionsStatus(
        DatasetVersion active,
        DatasetVersion lastImport,
        boolean importRunning,
        Map<String, Long> activeEntitiesBySchema) {}
