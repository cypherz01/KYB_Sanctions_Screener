package com.cypherz01.kyb.cache;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * A cached raw source payload. Entries are kept after {@code expiresAt}: an expired entry is still
 * useful as a stale fallback when the source is down (spec §5.2, §7).
 */
public record CacheEntry(String key, String source, JsonNode payload, Instant fetchedAt, Instant expiresAt) {

    public boolean isFresh(Instant now) {
        return now.isBefore(expiresAt);
    }
}
