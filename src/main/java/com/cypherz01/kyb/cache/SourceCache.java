package com.cypherz01.kyb.cache;

import java.util.Optional;

/** Durable cache of raw external-source payloads, shared by all source clients. */
public interface SourceCache {

    /** Returns the entry for {@code key} whether fresh or expired; callers decide what to do. */
    Optional<CacheEntry> find(String key);

    /** Inserts or replaces the entry for its key. */
    void put(CacheEntry entry);

    /** Drops the in-process tier only; the durable tier keeps its entries. */
    void clearLocal();

    /** Drops every tier. */
    void clearAll();
}
