package com.cypherz01.kyb.cache;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.function.Function;

/**
 * A value obtained from an external source plus its provenance. The raw {@code payload} is kept so
 * audit snapshots (M6) record exactly what the source returned, not our interpretation of it.
 *
 * @param fromCache served from cache rather than a live call
 * @param stale served from an expired cache entry because the source was unavailable
 */
public record Sourced<T>(T value, JsonNode payload, Instant fetchedAt, boolean fromCache, boolean stale) {

    public <R> Sourced<R> map(Function<? super T, ? extends R> fn) {
        return new Sourced<>(fn.apply(value), payload, fetchedAt, fromCache, stale);
    }
}
