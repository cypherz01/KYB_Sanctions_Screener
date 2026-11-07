package com.cypherz01.kyb.sanctions;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sanctions import settings. Defaults live in application.yml (spec §14).
 *
 * @param indexUrl OpenSanctions dataset index ({@code .../datasets/latest/<dataset>/index.json}).
 *     It names the current export version, the file URL and its SHA-1.
 * @param resourceName the file to import, as named in the index resources
 * @param schemas FtM schemas that are screened (spec §5.3)
 * @param topics an entity is imported only if it carries at least one of these topics
 * @param batchSize entities per insert batch (and per transaction)
 * @param keepVersions completed versions kept, including the active one (older ones are pruned)
 * @param maxMalformedLines unparseable lines tolerated before the import is failed
 * @param cron nightly import schedule ({@code -} disables it)
 * @param zone time zone the cron expression is evaluated in
 * @param importOnStartupIfEmpty start an import when the app boots with no active dataset
 */
@ConfigurationProperties("kyb.sanctions")
public record SanctionsProperties(
        URI indexUrl,
        String resourceName,
        Set<String> schemas,
        Set<String> topics,
        int batchSize,
        int keepVersions,
        int maxMalformedLines,
        Duration connectTimeout,
        Duration readTimeout,
        String cron,
        String zone,
        boolean importOnStartupIfEmpty) {}
