package com.cypherz01.kyb.scoring;

import java.time.Instant;

/** Provenance of a source's data as used by a check (from {@code Sourced}). */
public record SourceInfo(Instant fetchedAt, boolean fromCache, boolean stale) {}
